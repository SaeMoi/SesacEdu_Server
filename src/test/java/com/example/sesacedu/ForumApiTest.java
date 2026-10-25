package com.example.sesacedu;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * forum 패키지 6개 API가 Notion 명세(커뮤니티 메인 / 글 작성 / 글 불러오기 / 댓글 작성 / 글 삭제 / 댓글 삭제)대로 응답하는지 확인.
 * H2(Oracle 모드, application-test.yml + schema-test.sql)로 실행:
 *   gradlew test --tests com.example.sesacedu.ForumApiTest
 */
@SuppressWarnings("null") // VS Code(JDT) null 분석이 Spring/MockMvc API에 내는 경고 제외. 실행에는 영향 없음
@SpringBootTest(properties = "JWT_SECRET=" + ForumApiTest.SECRET)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ForumApiTest {

    static final String SECRET = "forum-api-test-secret-at-least-32-bytes-long!!";
    static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    long me;       // 일반 회원, 글 101·103 작성자
    long other;    // 글 102 작성자, 댓글 작성자
    long admin;    // users.auth = 3
    long banned;   // status = restricted

    @BeforeEach
    void setUp() {
        for (String t : new String[]{"comments", "posts", "reviews", "lectures", "users"}) {
            jdbc.update("DELETE FROM " + t);
        }
        jdbc.update("INSERT INTO users (mail, pw, uname, nickname, auth) VALUES ('me@example.test', 'x', '김새싹', '보안새싹', 1)");
        jdbc.update("INSERT INTO users (mail, pw, uname, nickname, auth) VALUES ('other@example.test', 'x', '이학습', '학습새싹', 1)");
        jdbc.update("INSERT INTO users (mail, pw, uname, nickname, auth) VALUES ('admin@example.test', 'x', '관리자', '운영자', 3)");
        jdbc.update("INSERT INTO users (mail, pw, uname, nickname, auth, status) VALUES ('ban@example.test', 'x', '정지됨', '정지', 1, 'restricted')");
        me = id("me@example.test");
        other = id("other@example.test");
        admin = id("admin@example.test");
        banned = id("ban@example.test");

        jdbc.update("INSERT INTO lectures (Lid, user_id, name, category, Lprice, status) VALUES (12, 9001, '웹 보안 기초', '웹 보안', 10000, 'open')");

        insertPost(101, me, "STUDY", "같은 속도로 배우는 사람들을 찾아요", "<p>혼자보다 함께,&nbsp;이번 주 <b>학습 목표</b>를 나눠봐요.</p>", "2026-10-13 14:10:00");
        insertPost(102, other, "QUESTION", "과제 제출 파일은 어떻게 하나요?", "<p>PDF 파일로</p><script>alert(1)</script><style>p{}</style><p>제출하는 방법이 궁금합니다.</p>", "2026-10-13 14:20:00");
        insertPost(103, me, "QUESTION", "웹 보안 기초를 함께 공부해요", "<p>" + "가".repeat(148) + "😀😀끝</p>", "2026-10-13 14:30:00");
        insertPost(104, other, "study", "예전 형식 카테고리", "<p>소문자 study는 세 카테고리가 아니라 목록에 나오지 않는다</p>", "2026-10-13 15:00:00");
        jdbc.update("INSERT INTO reviews (Lid, user_id, star, review, created) VALUES (12, ?, 5, ?, ?)",
                other, "인증과 인가를\n실습으로   이해할 수 있어서 좋았습니다.", Timestamp.valueOf("2026-10-13 14:25:00"));

        insertComment(501, 101, other, "저도 함께 공부하고 싶어요!", "2026-10-13 14:40:00");
        insertComment(502, 101, me, "좋아요\n주 2회 어때요?", "2026-10-13 14:50:00");
        insertComment(503, 101, other, "<b>굵게</b>", "2026-10-13 15:00:00");
        insertComment(504, 102, me, "102번 글 댓글", "2026-10-13 15:10:00");
    }

    long id(String mail) {
        return Objects.requireNonNull(jdbc.queryForObject("SELECT user_id FROM users WHERE mail = ?", Long.class, mail));
    }

    void insertPost(long pid, long uid, String category, String title, String content, String created) {
        jdbc.update("INSERT INTO posts (pid, user_id, category, title, content, created) VALUES (?, ?, ?, ?, ?, ?)",
                pid, uid, category, title, content, Timestamp.valueOf(created));
    }

    void insertComment(long cmid, long pid, long uid, String content, String created) {
        jdbc.update("INSERT INTO comments (cmid, pid, user_id, content, created) VALUES (?, ?, ?, ?, ?)",
                cmid, pid, uid, content, Timestamp.valueOf(created));
    }

    long count(String sql, Object... args) {
        return Objects.requireNonNull(jdbc.queryForObject(sql, Long.class, args));
    }

    static String kst(String ts) {
        return Timestamp.valueOf(ts).toInstant().atZone(ZoneId.of("Asia/Seoul")).format(ISO);
    }

    static String token(long sub, long secondsAgo) {
        return Jwts.builder().subject(String.valueOf(sub)).claim("role", "STUDENT").claim("reissue_num", 2)
                .expiration(new Date(System.currentTimeMillis() - secondsAgo * 1000))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)), Jwts.SIG.HS256).compact();
    }

    static String bearer(long sub) {
        return "Bearer " + token(sub, -3600);
    }

    // ================================================================ GET /forum

    @Test
    @DisplayName("GET /forum: 빈 sort = ALL, posts 세 카테고리 + 수강평을 한 목록으로 최신순, 소문자 study 글은 제외")
    void listAll() throws Exception {
        mvc.perform(get("/forum").param("sort", ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("FORUM_POSTS_FOUND"))
                .andExpect(jsonPath("$.message").value("커뮤니티 게시글을 조회했습니다."))
                .andExpect(jsonPath("$.result.selectedCategory").value("ALL"))
                .andExpect(jsonPath("$.result.posts[*].title").value(contains(
                        "웹 보안 기초를 함께 공부해요", "웹 보안 기초 수강평", "과제 제출 파일은 어떻게 하나요?", "같은 속도로 배우는 사람들을 찾아요")))
                .andExpect(jsonPath("$.result.posts[0].type").value("POST"))
                .andExpect(jsonPath("$.result.posts[0].postId").value(103))
                .andExpect(jsonPath("$.result.posts[0].reviewRef").value(nullValue()))
                .andExpect(jsonPath("$.result.posts[0].category.code").value("QUESTION"))
                .andExpect(jsonPath("$.result.posts[0].category.name").value("질문·답변"))
                .andExpect(jsonPath("$.result.posts[0].authorNickname").value("보안새싹"))
                .andExpect(jsonPath("$.result.posts[0].createdAt").value(kst("2026-10-13 14:30:00")))
                .andExpect(jsonPath("$.result.posts[0].lectureId").value(nullValue()))
                .andExpect(jsonPath("$.result.posts[0].lectureTitle").value(nullValue()))
                .andExpect(jsonPath("$.result.posts[0].star").value(nullValue()))
                .andExpect(jsonPath("$.result.posts[1].type").value("REVIEW"))
                .andExpect(jsonPath("$.result.posts[1].postId").value(nullValue()))
                .andExpect(jsonPath("$.result.posts[1].reviewRef.lectureId").value(12))
                .andExpect(jsonPath("$.result.posts[1].reviewRef.userId").value((int) other))
                .andExpect(jsonPath("$.result.posts[1].category.code").value("REVIEW"))
                .andExpect(jsonPath("$.result.posts[1].category.name").value("수강평"))
                .andExpect(jsonPath("$.result.posts[1].excerpt").value("인증과 인가를 실습으로 이해할 수 있어서 좋았습니다."))
                .andExpect(jsonPath("$.result.posts[1].authorNickname").value("학습새싹"))
                .andExpect(jsonPath("$.result.posts[1].lectureId").value(12))
                .andExpect(jsonPath("$.result.posts[1].lectureTitle").value("웹 보안 기초"))
                .andExpect(jsonPath("$.result.posts[1].star").value(5))
                .andExpect(jsonPath("$.result.page.number").value(0))
                .andExpect(jsonPath("$.result.page.size").value(15))
                .andExpect(jsonPath("$.result.page.totalElements").value(4))
                .andExpect(jsonPath("$.result.page.totalPages").value(1))
                .andExpect(jsonPath("$.result.page.hasNext").value(false))
                // 개인정보 없음
                .andExpect(jsonPath("$.result.posts[0].mail").doesNotExist())
                .andExpect(jsonPath("$.result.posts[0].userId").doesNotExist());
    }

    @Test
    @DisplayName("GET /forum: excerpt = HTML 서식·script·style 제거, 엔티티 디코딩, 150자(앞 149자 + …), 이모지 안 쪼갬")
    void listExcerpt() throws Exception {
        mvc.perform(get("/forum").param("sort", "ALL"))
                .andExpect(jsonPath("$.result.posts[2].excerpt").value("PDF 파일로 제출하는 방법이 궁금합니다."))
                .andExpect(jsonPath("$.result.posts[3].excerpt").value("혼자보다 함께, 이번 주 학습 목표를 나눠봐요."))
                .andExpect(jsonPath("$.result.posts[0].excerpt").value("가".repeat(148) + "😀…"));
    }

    @Test
    @DisplayName("GET /forum: 탭별 필터 (QUESTION / STUDY / PROJECT / REVIEW), 게시글 없으면 200 posts: []")
    void listByCategory() throws Exception {
        mvc.perform(get("/forum").param("sort", "QUESTION"))
                .andExpect(jsonPath("$.result.selectedCategory").value("QUESTION"))
                .andExpect(jsonPath("$.result.posts[*].postId").value(contains(103, 102)))
                .andExpect(jsonPath("$.result.page.totalElements").value(2));
        mvc.perform(get("/forum").param("sort", "STUDY"))
                .andExpect(jsonPath("$.result.posts[*].postId").value(contains(101)))
                .andExpect(jsonPath("$.result.page.totalElements").value(1));
        mvc.perform(get("/forum").param("sort", "REVIEW"))
                .andExpect(jsonPath("$.result.posts", hasSize(1)))
                .andExpect(jsonPath("$.result.posts[0].type").value("REVIEW"));
        mvc.perform(get("/forum").param("sort", "PROJECT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.selectedCategory").value("PROJECT"))
                .andExpect(jsonPath("$.result.posts", hasSize(0)))
                .andExpect(jsonPath("$.result.page.totalElements").value(0))
                .andExpect(jsonPath("$.result.page.totalPages").value(0))
                .andExpect(jsonPath("$.result.page.hasNext").value(false));
    }

    @Test
    @DisplayName("GET /forum: ALL은 합친 뒤 한 번만 페이징 (size=3 → 1페이지 3건 hasNext, 2페이지 1건), 범위 밖 페이지는 []")
    void listPaging() throws Exception {
        mvc.perform(get("/forum").param("size", "3"))
                .andExpect(jsonPath("$.result.posts", hasSize(3)))
                .andExpect(jsonPath("$.result.page.totalPages").value(2))
                .andExpect(jsonPath("$.result.page.hasNext").value(true));
        mvc.perform(get("/forum").param("size", "3").param("page", "1"))
                .andExpect(jsonPath("$.result.posts[*].postId").value(contains(101)))
                .andExpect(jsonPath("$.result.page.hasNext").value(false));
        mvc.perform(get("/forum").param("page", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.posts", hasSize(0)))
                .andExpect(jsonPath("$.result.page.totalElements").value(4));
    }

    @Test
    @DisplayName("GET /forum: 기본 15개씩 (16건 → page=0 15건·totalPages 2·hasNext, page=1 남은 1건)")
    void listDefaultPageSize15() throws Exception {
        for (int i = 0; i < 12; i++) {
            insertPost(200 + i, me, "PROJECT", "팀 프로젝트 " + i, "<p>본문 " + i + "</p>", "2026-10-14 10:" + String.format("%02d", i) + ":00");
        }
        mvc.perform(get("/forum"))
                .andExpect(jsonPath("$.result.posts", hasSize(15)))
                .andExpect(jsonPath("$.result.page.number").value(0))
                .andExpect(jsonPath("$.result.page.size").value(15))
                .andExpect(jsonPath("$.result.page.totalElements").value(16))
                .andExpect(jsonPath("$.result.page.totalPages").value(2))
                .andExpect(jsonPath("$.result.page.hasNext").value(true));
        mvc.perform(get("/forum").param("page", "1"))
                .andExpect(jsonPath("$.result.posts", hasSize(1)))
                .andExpect(jsonPath("$.result.posts[0].postId").value(101))
                .andExpect(jsonPath("$.result.page.hasNext").value(false));
    }

    @Test
    @DisplayName("GET /forum: page·size 오류는 400 INVALID_INPUT. [의도된 취약점 A03 SQLi] sort 는 더 이상 화이트리스트로 막지 않는다")
    void listInvalid() throws Exception {
        // sort=FREE 는 이제 거절되지 않고(화이트리스트 제거) default 분기로 간다. page·size 만 여전히 검증된다.
        mvc.perform(get("/forum").param("sort", "FREE").param("page", "-1").param("size", "51"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.message").value("입력값을 확인해주세요."))
                .andExpect(jsonPath("$.result.errors[*].field").value(contains("page", "size")));
        // sort=all 도 통과(검증 없음), size=abc 만 400
        mvc.perform(get("/forum").param("sort", "all").param("size", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.result.errors[*].field").value(contains("size")));
    }

    // ================================================================ POST /forum/posts

    @Test
    @DisplayName("POST /forum/posts: 201 + Location, 작성자 = 토큰 sub (요청의 userId 무시), 본문 원본 HTML 저장(의도된 저장형 XSS), updated null")
    void createPost() throws Exception {
        String json = "{\"category\":\"QUESTION\",\"title\":\"  웹 보안 기초를 함께 공부해요  \",\"userId\":" + other
                + ",\"content\":\"<p onclick=\\\"x()\\\">인증과 인가의 차이를 <strong>함께 정리</strong>해 보고 싶어요.</p><script>alert(1)</script><a href=\\\"javascript:alert(1)\\\">링크</a>\"}";
        String res = mvc.perform(post("/forum/posts").header("Authorization", bearer(me))
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("FORUM_POST_CREATED"))
                .andExpect(jsonPath("$.message").value("커뮤니티 게시글이 등록되었습니다."))
                .andExpect(jsonPath("$.result.postId").isNumber())
                .andExpect(jsonPath("$.result.category.code").value("QUESTION"))
                .andExpect(jsonPath("$.result.category.name").value("질문·답변"))
                .andExpect(jsonPath("$.result.title").value("웹 보안 기초를 함께 공부해요"))
                .andExpect(jsonPath("$.result.createdAt", matchesPattern("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\+09:00")))
                .andReturn().getResponse().getContentAsString();
        long pid = Long.parseLong(res.replaceAll(".*\"postId\":(\\d+).*", "$1"));

        var row = jdbc.queryForMap("SELECT user_id, category, title, content, updated FROM posts WHERE pid = ?", pid);
        assertThat(((Number) row.get("USER_ID")).longValue()).isEqualTo(me);
        assertThat(row.get("CATEGORY")).isEqualTo("QUESTION");
        assertThat(row.get("UPDATED")).isNull();
        String saved = jdbc.queryForObject("SELECT content FROM posts WHERE pid = ?", String.class, pid);
        // [의도된 취약점 — 저장형 XSS] 정제되지 않은 원본 HTML(스크립트·onclick·javascript: href)이 그대로 저장됨을 검증
        assertThat(saved).contains("<strong>함께 정리</strong>")
                .contains("<script>alert(1)</script>", "onclick=\"x()\"", "href=\"javascript:alert(1)\"");

        mvc.perform(get("/forum/" + pid)).andExpect(jsonPath("$.result.post.title").value("웹 보안 기초를 함께 공부해요"));
    }

    @Test
    @DisplayName("POST /forum/posts: Location 헤더 = /forum/{pid}")
    void createPostLocation() throws Exception {
        String res = mvc.perform(post("/forum/posts").header("Authorization", bearer(other))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"PROJECT\",\"title\":\"팀 프로젝트\",\"content\":\"그냥 텍스트\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getHeader("Location");
        assertThat(res).matches("/forum/\\d+");
    }

    @Test
    @DisplayName("POST /forum/posts: 카테고리·제목·본문 오류는 400, 틀린 항목 모두 errors[] (ALL·REVIEW 거절, 태그만 있는 본문 거절)")
    void createPostInvalid() throws Exception {
        mvc.perform(post("/forum/posts").header("Authorization", bearer(me)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"REVIEW\",\"title\":\"   \",\"content\":\"<p> </p><br><script>x</script>\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.result.errors[*].field").value(contains("category", "title", "content")))
                .andExpect(jsonPath("$.result.errors[0].reason").value("QUESTION, STUDY, PROJECT 중 하나여야 합니다."))
                .andExpect(jsonPath("$.result.errors[1].reason").value("제목을 입력해주세요."))
                .andExpect(jsonPath("$.result.errors[2].reason").value("내용을 입력해주세요."));
        mvc.perform(post("/forum/posts").header("Authorization", bearer(me)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"ALL\",\"title\":\"" + "가".repeat(201) + "\",\"content\":\"" + "a".repeat(10_001) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.result.errors[0].field").value("category"))
                .andExpect(jsonPath("$.result.errors[1].reason").value("제목은 200자 이하로 입력해주세요."))
                .andExpect(jsonPath("$.result.errors[2].reason").value("본문은 10,000자 이하로 입력해주세요."));
        mvc.perform(post("/forum/posts").header("Authorization", bearer(me)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"제목\",\"content\":\"" + "a".repeat(50_001) + "\"}"))
                .andExpect(jsonPath("$.result.errors[0].reason").value("카테고리를 선택해주세요."))
                .andExpect(jsonPath("$.result.errors[1].reason").value("본문은 50,000자 이하로 보내주세요."));
        mvc.perform(post("/forum/posts").header("Authorization", bearer(me)).contentType(MediaType.APPLICATION_JSON).content("{bad json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.result.errors[0].field").value("body"));
        assertThat(count("SELECT COUNT(*) FROM posts")).isEqualTo(4);
    }

    @Test
    @DisplayName("POST /forum/posts: 토큰 없음·위조 401 UNAUTHORIZED, 만료 401 TOKEN_EXPIRED, 없는 회원 401, restricted 403 (입력 검증보다 먼저)")
    void createPostAuth() throws Exception {
        String body = "{\"category\":\"PROJECT\",\"title\":\"t\",\"content\":\"c\"}";
        mvc.perform(post("/forum/posts").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.result").value(nullValue()));
        mvc.perform(post("/forum/posts").header("Authorization", "Bearer abc.def.ghi").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mvc.perform(post("/forum/posts").header("Authorization", "Bearer " + token(me, 60)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("TOKEN_EXPIRED"));
        mvc.perform(post("/forum/posts").header("Authorization", bearer(999_999)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mvc.perform(post("/forum/posts").header("Authorization", bearer(banned)).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCOUNT_RESTRICTED"))
                .andExpect(jsonPath("$.result").value(nullValue()));
        assertThat(count("SELECT COUNT(*) FROM posts")).isEqualTo(4);
    }

    // ================================================================ GET /forum/{pid}

    @Test
    @DisplayName("GET /forum/{pid}: 비로그인 200, 본문 HTML 전체 + 댓글 오래된 순, isMine 모두 false")
    void detailAnonymous() throws Exception {
        mvc.perform(get("/forum/101"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("FORUM_POST_FOUND"))
                .andExpect(jsonPath("$.message").value("커뮤니티 게시글을 조회했습니다."))
                .andExpect(jsonPath("$.result.post.postId").value(101))
                .andExpect(jsonPath("$.result.post.category.code").value("STUDY"))
                .andExpect(jsonPath("$.result.post.category.name").value("스터디"))
                .andExpect(jsonPath("$.result.post.title").value("같은 속도로 배우는 사람들을 찾아요"))
                .andExpect(jsonPath("$.result.post.content").value("<p>혼자보다 함께,&nbsp;이번 주 <b>학습 목표</b>를 나눠봐요.</p>"))
                .andExpect(jsonPath("$.result.post.contentFormat").value("HTML"))
                .andExpect(jsonPath("$.result.post.authorNickname").value("보안새싹"))
                .andExpect(jsonPath("$.result.post.createdAt").value(kst("2026-10-13 14:10:00")))
                .andExpect(jsonPath("$.result.post.updatedAt").value(nullValue()))
                .andExpect(jsonPath("$.result.post.isMine").value(false))
                .andExpect(jsonPath("$.result.comments[*].commentId").value(contains(501, 502, 503)))
                .andExpect(jsonPath("$.result.comments[0].content").value("저도 함께 공부하고 싶어요!"))
                .andExpect(jsonPath("$.result.comments[0].authorNickname").value("학습새싹"))
                .andExpect(jsonPath("$.result.comments[0].createdAt").value(kst("2026-10-13 14:40:00")))
                .andExpect(jsonPath("$.result.comments[0].updatedAt").value(nullValue()))
                .andExpect(jsonPath("$.result.comments[1].content").value("좋아요\n주 2회 어때요?"))
                .andExpect(jsonPath("$.result.comments[2].content").value("<b>굵게</b>"))
                .andExpect(jsonPath("$.result.comments[*].isMine").value(contains(false, false, false)))
                .andExpect(jsonPath("$.result.page.totalElements").value(3))
                .andExpect(jsonPath("$.result.post.mail").doesNotExist())
                .andExpect(jsonPath("$.result.post.userId").doesNotExist());
    }

    @Test
    @DisplayName("GET /forum/{pid}: 로그인 토큰이 있으면 본인 글·댓글만 isMine true, 위조·만료 토큰은 비로그인처럼 200")
    void detailIsMine() throws Exception {
        mvc.perform(get("/forum/101").header("Authorization", bearer(me)))
                .andExpect(jsonPath("$.result.post.isMine").value(true))
                .andExpect(jsonPath("$.result.comments[*].isMine").value(contains(false, true, false)));
        mvc.perform(get("/forum/101").header("Authorization", bearer(other)))
                .andExpect(jsonPath("$.result.post.isMine").value(false))
                .andExpect(jsonPath("$.result.comments[*].isMine").value(contains(true, false, true)));
        mvc.perform(get("/forum/101").header("Authorization", bearer(admin)))    // 관리자라도 isMine은 본인 것만
                .andExpect(jsonPath("$.result.post.isMine").value(false));
        mvc.perform(get("/forum/101").header("Authorization", "Bearer " + token(me, 60)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.post.isMine").value(false));
        mvc.perform(get("/forum/101").header("Authorization", "Bearer garbage"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.post.isMine").value(false));
    }

    @Test
    @DisplayName("GET /forum/{pid}: 댓글 페이징 (size=2 page=1 → 503), 범위 밖은 comments: [], 댓글 없는 글도 200")
    void detailCommentPaging() throws Exception {
        mvc.perform(get("/forum/101").param("size", "2").param("page", "1"))
                .andExpect(jsonPath("$.result.comments[*].commentId").value(contains(503)))
                .andExpect(jsonPath("$.result.page.number").value(1))
                .andExpect(jsonPath("$.result.page.size").value(2))
                .andExpect(jsonPath("$.result.page.totalElements").value(3))
                .andExpect(jsonPath("$.result.page.totalPages").value(2))
                .andExpect(jsonPath("$.result.page.hasNext").value(false));
        mvc.perform(get("/forum/101").param("page", "9"))
                .andExpect(jsonPath("$.result.post.postId").value(101))
                .andExpect(jsonPath("$.result.comments", hasSize(0)));
        mvc.perform(get("/forum/103"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.comments", hasSize(0)))
                .andExpect(jsonPath("$.result.page.totalElements").value(0));
    }

    @Test
    @DisplayName("GET /forum/{pid}: 없는 글 404 POST_NOT_FOUND, pid·page·size 형식 오류 400")
    void detailErrors() throws Exception {
        mvc.perform(get("/forum/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("POST_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("게시글을 찾을 수 없습니다."))
                .andExpect(jsonPath("$.result").value(nullValue()));
        mvc.perform(get("/forum/abc").param("page", "-1").param("size", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.result.errors[*].field").value(contains("pid", "page", "size")))
                .andExpect(jsonPath("$.result.errors[0].reason").value("양의 정수여야 합니다."));
        mvc.perform(get("/forum/0")).andExpect(status().isBadRequest());
    }

    // ================================================================ POST /forum/{pid}/comment

    @Test
    @DisplayName("POST /forum/{pid}/comment: 201, 작성자 = 토큰 sub, 앞뒤 공백 제거·줄바꿈 유지, HTML은 문자 그대로, 상세 comments[] 형태")
    void createComment() throws Exception {
        String res = mvc.perform(post("/forum/101/comment").header("Authorization", bearer(other))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"  <b>저도</b>\\n함께해요  \",\"userId\":" + me + "}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("FORUM_COMMENT_CREATED"))
                .andExpect(jsonPath("$.message").value("댓글이 등록되었습니다."))
                .andExpect(jsonPath("$.result.commentId").isNumber())
                .andExpect(jsonPath("$.result.content").value("<b>저도</b>\n함께해요"))
                .andExpect(jsonPath("$.result.authorNickname").value("학습새싹"))
                .andExpect(jsonPath("$.result.createdAt", matchesPattern("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\+09:00")))
                .andExpect(jsonPath("$.result.updatedAt").value(nullValue()))
                .andExpect(jsonPath("$.result.isMine").value(true))
                .andReturn().getResponse().getContentAsString();
        long cmid = Long.parseLong(res.replaceAll(".*\"commentId\":(\\d+).*", "$1"));
        assertThat(count("SELECT user_id FROM comments WHERE cmid = ? AND pid = 101", cmid)).isEqualTo(other);
        mvc.perform(get("/forum/101"))
                .andExpect(jsonPath("$.result.page.totalElements").value(4))
                .andExpect(jsonPath("$.result.comments[3].commentId").value((int) cmid));
    }

    @Test
    @DisplayName("POST /forum/{pid}/comment: 공백뿐·1,000자 초과·pid 오류 400, 없는 글 404, restricted 403, 토큰 없음 401")
    void createCommentErrors() throws Exception {
        mvc.perform(post("/forum/abc/comment").header("Authorization", bearer(me))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\" \\n \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.result.errors[*].field").value(contains("pid", "content")))
                .andExpect(jsonPath("$.result.errors[1].reason").value("댓글은 1자 이상 1,000자 이하로 입력해주세요."));
        mvc.perform(post("/forum/101/comment").header("Authorization", bearer(me))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"" + "a".repeat(1001) + "\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/forum/101/comment").header("Authorization", bearer(me))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"" + "😀".repeat(1000) + "\"}"))
                .andExpect(status().isCreated());   // 이모지 1,000개 = 1,000자
        mvc.perform(post("/forum/999/comment").header("Authorization", bearer(me))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"안녕\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("POST_NOT_FOUND"));
        mvc.perform(post("/forum/101/comment").header("Authorization", bearer(banned))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"안녕\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCOUNT_RESTRICTED"));
        mvc.perform(post("/forum/101/comment").contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"안녕\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        assertThat(count("SELECT COUNT(*) FROM comments")).isEqualTo(5);
    }

    // ================================================================ DELETE /forum/{pid}

    @Test
    @DisplayName("DELETE /forum/{pid}: 작성자 200, 댓글은 cascade로 함께 삭제, 다시 삭제하면 404")
    void deletePostByAuthor() throws Exception {
        mvc.perform(delete("/forum/101").header("Authorization", bearer(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("FORUM_POST_DELETED"))
                .andExpect(jsonPath("$.message").value("게시글이 삭제되었습니다."))
                .andExpect(jsonPath("$.result.postId").value(101))
                .andExpect(jsonPath("$.result.deletedCommentCount").value(3));
        assertThat(count("SELECT COUNT(*) FROM posts WHERE pid = 101")).isZero();
        assertThat(count("SELECT COUNT(*) FROM comments WHERE pid = 101")).isZero();
        assertThat(count("SELECT COUNT(*) FROM comments WHERE pid = 102")).isEqualTo(1);
        mvc.perform(delete("/forum/101").header("Authorization", bearer(me)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("POST_NOT_FOUND"));
        mvc.perform(get("/forum/101")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("DELETE /forum/{pid}: [의도된 취약점 A01 IDOR] 작성자·관리자 확인이 없어 로그인만 하면 남의 글도 200으로 삭제")
    void deletePostPermission() throws Exception {
        // me 는 102(other 의 글)의 작성자가 아니지만 삭제된다 = 객체 수준 인가 누락
        mvc.perform(delete("/forum/102").header("Authorization", bearer(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("FORUM_POST_DELETED"))
                .andExpect(jsonPath("$.result.postId").value(102))
                .andExpect(jsonPath("$.result.deletedCommentCount").value(1));
        assertThat(count("SELECT COUNT(*) FROM posts WHERE pid = 102")).isZero();
    }

    @Test
    @DisplayName("DELETE /forum/{pid}: [의도된 취약점 A01] 작성자 확인이 없어 아무 로그인 토큰이나 남의 글을 삭제한다")
    void deletePostRoleFromDb() throws Exception {
        String anyToken = Jwts.builder().subject(String.valueOf(me)).claim("role", "STUDENT").claim("reissue_num", 2)
                .expiration(new Date(System.currentTimeMillis() + 3_600_000))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)), Jwts.SIG.HS256).compact();
        mvc.perform(delete("/forum/102").header("Authorization", "Bearer " + anyToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("FORUM_POST_DELETED"));
    }

    @Test
    @DisplayName("DELETE /forum/{pid}: pid 오류 400, restricted 403 (작성자여도), 토큰 없음 401, 만료 401 TOKEN_EXPIRED")
    void deletePostErrors() throws Exception {
        mvc.perform(delete("/forum/-3").header("Authorization", bearer(me)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.result.errors[0].field").value("pid"))
                .andExpect(jsonPath("$.result.errors[0].reason").value("양의 정수여야 합니다."));
        jdbc.update("UPDATE users SET status = 'restricted' WHERE user_id = ?", me);
        mvc.perform(delete("/forum/101").header("Authorization", bearer(me)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCOUNT_RESTRICTED"));
        mvc.perform(delete("/forum/101"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mvc.perform(delete("/forum/101").header("Authorization", "Bearer " + token(other, 60)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TOKEN_EXPIRED"));
        assertThat(count("SELECT COUNT(*) FROM posts WHERE pid = 101")).isEqualTo(1);
    }

    // ================================================================ DELETE /forum/{pid}/comment

    @Test
    @DisplayName("DELETE /forum/{pid}/comment: 댓글 작성자 200, 다시 삭제하면 404 COMMENT_NOT_FOUND")
    void deleteCommentByAuthor() throws Exception {
        mvc.perform(delete("/forum/101/comment").param("commentId", "502").header("Authorization", bearer(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("FORUM_COMMENT_DELETED"))
                .andExpect(jsonPath("$.message").value("댓글이 삭제되었습니다."))
                .andExpect(jsonPath("$.result.postId").value(101))
                .andExpect(jsonPath("$.result.commentId").value(502));
        assertThat(count("SELECT COUNT(*) FROM comments WHERE cmid = 502")).isZero();
        assertThat(count("SELECT COUNT(*) FROM comments WHERE pid = 101")).isEqualTo(2);
        mvc.perform(delete("/forum/101/comment").param("commentId", "502").header("Authorization", bearer(me)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COMMENT_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("댓글을 찾을 수 없습니다."));
    }

    @Test
    @DisplayName("DELETE /forum/{pid}/comment: [의도된 취약점 A01 IDOR] 댓글 작성자 확인이 없어 남의 댓글도 200으로 삭제")
    void deleteCommentPermission() throws Exception {
        // 501 은 me 의 댓글이 아니라 other 의 댓글이지만 삭제된다 = 객체 수준 인가 누락
        mvc.perform(delete("/forum/101/comment").param("commentId", "501").header("Authorization", bearer(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("FORUM_COMMENT_DELETED"))
                .andExpect(jsonPath("$.result.commentId").value(501));
        assertThat(count("SELECT COUNT(*) FROM comments WHERE cmid = 501")).isZero();
    }

    @Test
    @DisplayName("DELETE /forum/{pid}/comment: 다른 글의 댓글 ID는 404, pid·commentId 오류 400 (둘 다 errors[]), restricted 403")
    void deleteCommentErrors() throws Exception {
        mvc.perform(delete("/forum/101/comment").param("commentId", "504").header("Authorization", bearer(me)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COMMENT_NOT_FOUND"));
        assertThat(count("SELECT COUNT(*) FROM comments WHERE cmid = 504")).isEqualTo(1);
        mvc.perform(delete("/forum/x/comment").header("Authorization", bearer(me)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.result.errors[*].field").value(contains("pid", "commentId")));
        mvc.perform(delete("/forum/101/comment").param("commentId", "1.5").header("Authorization", bearer(me)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.result.errors[0].field").value("commentId"));
        mvc.perform(delete("/forum/101/comment").param("commentId", "501").header("Authorization", bearer(banned)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCOUNT_RESTRICTED"));
    }
}
