package com.example.sesacedu;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.Objects;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * mypage 패키지의 courses(GET /mypage/courses) / qna(GET /mypage/qna)가 Notion 명세(내 학습, 내 Q&A)대로 응답하는지 확인.
 * H2(Oracle 모드, application-test.yml + schema-test.sql)로 실행:
 *   gradlew test --tests com.example.sesacedu.MypageLearningApiTest
 */
@SuppressWarnings("null") // VS Code(JDT) null 분석이 Spring/MockMvc API에 내는 경고 제외. 실행에는 영향 없음
@SpringBootTest(properties = "JWT_SECRET=" + MypageLearningApiTest.SECRET)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MypageLearningApiTest {

    static final String SECRET = "mypage-learning-test-secret-at-least-32-bytes!!";
    static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    long me;
    long other;
    long empty;

    @BeforeEach
    void setUp() {
        for (String t : new String[]{"qna", "reviews", "enrollments", "lectures", "instructors", "users"}) {
            jdbc.update("DELETE FROM " + t);
        }
        jdbc.update("INSERT INTO users (mail, pw, uname, nickname, auth) VALUES ('me@example.test', 'x', '김새싹', '새싹', 1)");
        jdbc.update("INSERT INTO users (mail, pw, uname, nickname, auth) VALUES ('other@example.test', 'x', '이타인', '타인', 1)");
        jdbc.update("INSERT INTO users (mail, pw, uname, nickname, auth) VALUES ('empty@example.test', 'x', '빈사람', '빈', 0)");
        me = id("me@example.test");
        other = id("other@example.test");
        empty = id("empty@example.test");

        // 강사 9001만 instructors 행이 있다 (9002는 없음 → lecturer null)
        jdbc.update("INSERT INTO instructors (user_id, iname) VALUES (9001, '박강사')");
        lecture(1, 9001, "웹 해킹 입문", "웹 보안", "/images/lecture/1.png", "/materials/1.pdf", "open");
        lecture(2, 9001, "네트워크 기초", "네트워크", null, null, "open");
        lecture(3, 9002, "가상화 입문", "가상화", null, "/materials/3.pdf", "closed"); // 매핑 없는 카테고리 → ETC
        lecture(4, 9001, "남의 강의", "클라우드", null, null, "open");

        enroll(me, 1, 0, "2026-10-02 14:30:00");
        enroll(me, 2, 1, "2026-10-05 09:10:00");
        enroll(me, 3, 0, null);                          // enrolled_at 없음 → 강좌 3의 enrolledAt은 null
        enroll(other, 1, 1, "2026-10-10 10:00:00");
        enroll(other, 4, 0, null);

        jdbc.update("INSERT INTO reviews (Lid, user_id, star) VALUES (2, ?, 5)", me);
        jdbc.update("INSERT INTO reviews (Lid, user_id, star) VALUES (1, ?, 4)", other); // 남의 리뷰는 내 reviewWritten에 영향 없음

        qna(1, 1, me, "세션 질문", "답변입니다", "2026-10-08 09:12:00", 0, "2026-10-07 14:20:00");
        qna(2, 3, me, "가상머신 설치", null, "2026-10-07 00:00:00", 1, "2026-10-06 16:30:00"); // answer 없음 → answered_at 있어도 null
        qna(3, 2, me, "패킷 캡처", "확인했습니다", "2026-10-09 12:00:00", 0, "2026-10-09 08:00:00");
        qna(4, 1, other, "남의 질문", null, null, 1, "2026-10-10 08:00:00");
    }

    long id(String mail) {
        return Objects.requireNonNull(jdbc.queryForObject("SELECT user_id FROM users WHERE mail = ?", Long.class, mail));
    }

    void lecture(long lid, long inst, String name, String category, String picture, String material, String status) {
        jdbc.update("INSERT INTO lectures (Lid, user_id, name, category, picture, material, Lprice, status) VALUES (?, ?, ?, ?, ?, ?, 10000, ?)",
                lid, inst, name, category, picture, material, status);
    }

    void enroll(long uid, long lid, int done, String enrolledAt) {
        jdbc.update("INSERT INTO enrollments (user_id, Lid, done, enrolled_at) VALUES (?, ?, ?, ?)",
                uid, lid, done, enrolledAt == null ? null : Timestamp.valueOf(enrolledAt));
    }

    void qna(long qid, long lid, long uid, String title, String answer, String answeredAt, int secret, String created) {
        jdbc.update("INSERT INTO qna (qid, Lid, user_id, title, question, answer, answered_at, isSecret, created) VALUES (?, ?, ?, ?, '질문 본문', ?, ?, ?, ?)",
                qid, lid, uid, title, answer, answeredAt == null ? null : Timestamp.valueOf(answeredAt), secret, Timestamp.valueOf(created));
    }

    /** DB에 넣은 시각을 서버와 같은 방식(Asia/Seoul, +09:00)으로 */
    static String kst(String ts) {
        return Timestamp.valueOf(ts).toInstant().atZone(ZoneId.of("Asia/Seoul")).format(ISO);
    }

    static String token(long sub, long secondsAgo, String secret) {
        return Jwts.builder().subject(String.valueOf(sub)).claim("role", "STUDENT").claim("reissue_num", 2)
                .expiration(new Date(System.currentTimeMillis() - secondsAgo * 1000))
                .signWith(Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8)), Jwts.SIG.HS256).compact();
    }

    static String bearer(long sub) {
        return "Bearer " + token(sub, -3600, SECRET);
    }

    // ------------------------------------------------------------ GET /mypage/courses

    @Test @DisplayName("내 학습 200: 기본(ALL, 최근 구매순) — 필드, 건수, enrolledAt = enrollments.enrolled_at, 값 없는 강좌 맨 뒤, 남의 수강 제외")
    void coursesDefault() throws Exception {
        mvc.perform(get("/mypage/courses").header("Authorization", bearer(me)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("MY_COURSES_FOUND"))
                .andExpect(jsonPath("$.message").value("내 학습 목록을 조회했습니다."))
                .andExpect(jsonPath("$.result.counts.all").value(3))
                .andExpect(jsonPath("$.result.counts.inProgress").value(2))
                .andExpect(jsonPath("$.result.counts.completed").value(1))
                .andExpect(jsonPath("$.result.courses.length()").value(3))
                .andExpect(jsonPath("$.result.courses[*].courseId", contains(2, 1, 3)))
                // 강좌 2: 완료, 리뷰 있음, 사진·자료 없음
                .andExpect(jsonPath("$.result.courses[0].category.code").value("NETWORK"))
                .andExpect(jsonPath("$.result.courses[0].category.name").value("네트워크"))
                .andExpect(jsonPath("$.result.courses[0].title").value("네트워크 기초"))
                .andExpect(jsonPath("$.result.courses[0].thumbnailUrl").value(nullValue()))
                .andExpect(jsonPath("$.result.courses[0].status").value("COMPLETED"))
                .andExpect(jsonPath("$.result.courses[0].enrolledAt").value(kst("2026-10-05 09:10:00")))
                .andExpect(jsonPath("$.result.courses[0].reviewWritten").value(true))
                .andExpect(jsonPath("$.result.courses[0].hasMaterials").value(false))
                .andExpect(jsonPath("$.result.courses[0].length()").value(8))
                // 강좌 1: 수강 중, 남의 리뷰·남의 주문은 무시
                .andExpect(jsonPath("$.result.courses[1].category.code").value("WEB_SECURITY"))
                .andExpect(jsonPath("$.result.courses[1].thumbnailUrl").value("/images/lecture/1.png"))
                .andExpect(jsonPath("$.result.courses[1].status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.result.courses[1].enrolledAt").value(kst("2026-10-02 14:30:00")))
                .andExpect(jsonPath("$.result.courses[1].reviewWritten").value(false))
                .andExpect(jsonPath("$.result.courses[1].hasMaterials").value(true))
                // 강좌 3: enrolled_at 없음 → enrolledAt null, 모르는 카테고리 → ETC
                .andExpect(jsonPath("$.result.courses[2].category.code").value("ETC"))
                .andExpect(jsonPath("$.result.courses[2].category.name").value("가상화"))
                .andExpect(jsonPath("$.result.courses[2].enrolledAt").value(nullValue()))
                .andExpect(jsonPath("$.result.page.number").value(0))
                .andExpect(jsonPath("$.result.page.size").value(10))
                .andExpect(jsonPath("$.result.page.totalElements").value(3))
                .andExpect(jsonPath("$.result.page.totalPages").value(1))
                .andExpect(jsonPath("$.result.page.hasNext").value(false));
    }

    @Test @DisplayName("내 학습 200: status 필터 — 목록·totalElements는 필터 기준, counts는 항상 전체")
    void coursesStatusFilter() throws Exception {
        mvc.perform(get("/mypage/courses?status=COMPLETED").header("Authorization", bearer(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.courses[*].courseId", contains(2)))
                .andExpect(jsonPath("$.result.page.totalElements").value(1))
                .andExpect(jsonPath("$.result.counts.all").value(3));
        mvc.perform(get("/mypage/courses?status=IN_PROGRESS").header("Authorization", bearer(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.courses[*].courseId", contains(1, 3)))
                .andExpect(jsonPath("$.result.page.totalElements").value(2))
                .andExpect(jsonPath("$.result.counts.completed").value(1));
    }

    @Test @DisplayName("내 학습 200: sort=TITLE 강좌명순, 빈 쿼리(?status=&sort=)는 기본값")
    void coursesSort() throws Exception {
        mvc.perform(get("/mypage/courses?sort=TITLE").header("Authorization", bearer(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.courses[*].title", contains("가상화 입문", "네트워크 기초", "웹 해킹 입문")));
        mvc.perform(get("/mypage/courses?status=&sort=").header("Authorization", bearer(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.courses[*].courseId", contains(2, 1, 3)));
    }

    @Test @DisplayName("내 학습 200: 페이징 page·size, hasNext")
    void coursesPaging() throws Exception {
        mvc.perform(get("/mypage/courses?page=1&size=1").header("Authorization", bearer(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.courses[*].courseId", contains(1)))
                .andExpect(jsonPath("$.result.page.number").value(1))
                .andExpect(jsonPath("$.result.page.size").value(1))
                .andExpect(jsonPath("$.result.page.totalPages").value(3))
                .andExpect(jsonPath("$.result.page.hasNext").value(true));
        mvc.perform(get("/mypage/courses?page=5&size=50").header("Authorization", bearer(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.courses", hasSize(0)))
                .andExpect(jsonPath("$.result.page.hasNext").value(false));
    }

    @Test @DisplayName("내 학습 200: 수강 강좌가 없으면 courses: [], 건수 0")
    void coursesEmpty() throws Exception {
        mvc.perform(get("/mypage/courses").header("Authorization", bearer(empty)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.courses", hasSize(0)))
                .andExpect(jsonPath("$.result.counts.all").value(0))
                .andExpect(jsonPath("$.result.counts.inProgress").value(0))
                .andExpect(jsonPath("$.result.counts.completed").value(0))
                .andExpect(jsonPath("$.result.page.totalElements").value(0))
                .andExpect(jsonPath("$.result.page.totalPages").value(0));
    }

    @Test @DisplayName("내 학습 400: status·sort·page·size 오류를 result.errors로 모두")
    void coursesInvalid() throws Exception {
        mvc.perform(get("/mypage/courses?status=DONE&sort=PROGRESS&page=-1&size=51").header("Authorization", bearer(me)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.message").value("입력값을 확인해주세요."))
                .andExpect(jsonPath("$.result.errors[*].field", contains("status", "sort", "page", "size")))
                .andExpect(jsonPath("$.result.errors[0].reason").value("ALL, IN_PROGRESS, COMPLETED 중 하나여야 합니다."));
        mvc.perform(get("/mypage/courses?size=0").header("Authorization", bearer(me)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.result.errors[*].field", contains("size")));
        mvc.perform(get("/mypage/courses?size=abc&page=x").header("Authorization", bearer(me)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.result.errors[*].field", contains("page", "size")));
    }

    @Test @DisplayName("내 학습 401: 토큰 없음·위조 UNAUTHORIZED, 만료 TOKEN_EXPIRED (쿼리 오류보다 먼저)")
    void coursesUnauthorized() throws Exception {
        mvc.perform(get("/mypage/courses?status=WRONG"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.result").value(nullValue()));
        mvc.perform(get("/mypage/courses").header("Authorization", "Bearer " + token(me, -3600, "some-other-secret-at-least-32-bytes-long!!")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mvc.perform(get("/mypage/courses").header("Authorization", "Bearer " + token(me, 60, SECRET)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TOKEN_EXPIRED"));
    }

    // ------------------------------------------------------------ GET /mypage/qna

    @Test @DisplayName("내 Q&A 200: 기본(ALL, 최신순) — 필드, 건수, 담당 강사, 비밀글, 남의 질문 제외")
    void qnaDefault() throws Exception {
        mvc.perform(get("/mypage/qna").header("Authorization", bearer(me)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("MY_QNA_FOUND"))
                .andExpect(jsonPath("$.message").value("내 Q&A 목록을 조회했습니다."))
                .andExpect(jsonPath("$.result.counts.all").value(3))
                .andExpect(jsonPath("$.result.counts.pending").value(1))
                .andExpect(jsonPath("$.result.counts.answered").value(2))
                .andExpect(jsonPath("$.result.questions[*].qnaId", contains(3, 1, 2)))
                .andExpect(jsonPath("$.result.questions[1].qnaId").value(1))
                .andExpect(jsonPath("$.result.questions[1].lectureId").value(1))
                .andExpect(jsonPath("$.result.questions[1].lectureTitle").value("웹 해킹 입문"))
                .andExpect(jsonPath("$.result.questions[1].title").value("세션 질문"))
                .andExpect(jsonPath("$.result.questions[1].lecturer").value("박강사"))
                .andExpect(jsonPath("$.result.questions[1].status").value("ANSWERED"))
                .andExpect(jsonPath("$.result.questions[1].isSecret").value(false))
                .andExpect(jsonPath("$.result.questions[1].createdAt").value(kst("2026-10-07 14:20:00")))
                .andExpect(jsonPath("$.result.questions[1].answeredAt").value(kst("2026-10-08 09:12:00")))
                .andExpect(jsonPath("$.result.questions[1].length()").value(9))
                .andExpect(jsonPath("$.result.questions[1].question").doesNotExist())
                // 질문 2: 강사 행 없음 → lecturer null, answer 없으면 answered_at이 있어도 answeredAt null
                .andExpect(jsonPath("$.result.questions[2].lecturer").value(nullValue()))
                .andExpect(jsonPath("$.result.questions[2].status").value("PENDING"))
                .andExpect(jsonPath("$.result.questions[2].isSecret").value(true))
                .andExpect(jsonPath("$.result.questions[2].answeredAt").value(nullValue()))
                .andExpect(jsonPath("$.result.page.totalElements").value(3))
                .andExpect(jsonPath("$.result.page.hasNext").value(false));
    }

    @Test @DisplayName("내 Q&A 200: status 필터 — 목록·totalElements는 필터 기준, counts는 항상 전체")
    void qnaStatusFilter() throws Exception {
        mvc.perform(get("/mypage/qna?status=PENDING").header("Authorization", bearer(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.questions[*].qnaId", contains(2)))
                .andExpect(jsonPath("$.result.page.totalElements").value(1))
                .andExpect(jsonPath("$.result.counts.all").value(3));
        mvc.perform(get("/mypage/qna?status=ANSWERED").header("Authorization", bearer(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.questions[*].qnaId", contains(3, 1)))
                .andExpect(jsonPath("$.result.page.totalElements").value(2));
    }

    @Test @DisplayName("내 Q&A 200: sort=TITLE 제목순, sort=STATUS 답변 대기 먼저")
    void qnaSort() throws Exception {
        mvc.perform(get("/mypage/qna?sort=TITLE").header("Authorization", bearer(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.questions[*].title", contains("가상머신 설치", "세션 질문", "패킷 캡처")));
        mvc.perform(get("/mypage/qna?sort=STATUS").header("Authorization", bearer(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.questions[*].qnaId", contains(2, 3, 1)));
    }

    @Test @DisplayName("내 Q&A 200: 페이징, 질문이 없으면 questions: []")
    void qnaPagingAndEmpty() throws Exception {
        mvc.perform(get("/mypage/qna?page=1&size=2").header("Authorization", bearer(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.questions[*].qnaId", contains(2)))
                .andExpect(jsonPath("$.result.page.totalPages").value(2))
                .andExpect(jsonPath("$.result.page.hasNext").value(false));
        mvc.perform(get("/mypage/qna").header("Authorization", bearer(empty)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.questions", hasSize(0)))
                .andExpect(jsonPath("$.result.counts.all").value(0));
    }

    @Test @DisplayName("내 Q&A 400: status·sort·page·size 오류를 result.errors로 모두")
    void qnaInvalid() throws Exception {
        mvc.perform(get("/mypage/qna?status=DONE&sort=OLD&page=-1&size=0").header("Authorization", bearer(me)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.result.errors[*].field", contains("status", "sort", "page", "size")))
                .andExpect(jsonPath("$.result.errors[0].reason").value("ALL, PENDING, ANSWERED 중 하나여야 합니다."))
                .andExpect(jsonPath("$.result.errors[1].reason").value("RECENT, TITLE, STATUS 중 하나여야 합니다."));
    }

    @Test @DisplayName("내 Q&A 401: 토큰 없음 UNAUTHORIZED, 만료 TOKEN_EXPIRED")
    void qnaUnauthorized() throws Exception {
        mvc.perform(get("/mypage/qna"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mvc.perform(get("/mypage/qna").header("Authorization", "Bearer " + token(me, 60, SECRET)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TOKEN_EXPIRED"));
    }
}
