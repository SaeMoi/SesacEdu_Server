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
import java.util.Date;
import java.util.Objects;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * mypage.cstqna (고객센터 1:1 문의) 5개 API가 Notion 명세대로 응답하는지 확인.
 * H2(Oracle 모드, application-test.yml + schema-test.sql)로 실행:
 *   gradlew test --tests com.example.sesacedu.MypageCstQnaApiTest
 */
@SuppressWarnings("null") // VS Code(JDT) null 분석이 Spring/MockMvc API에 내는 경고 제외. 실행에는 영향 없음
@SpringBootTest(properties = "JWT_SECRET=" + MypageCstQnaApiTest.SECRET)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MypageCstQnaApiTest {

    static final String SECRET = "mypage-cstqna-test-secret-at-least-32-bytes!!";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    long me;
    long other;
    long banned;
    long admin;
    long pending1;   // 내 미답변 (오래된 것)
    long answered;   // 내 답변 완료
    long pending2;   // 내 미답변 (최신)
    long othersQ;    // 남의 문의

    @BeforeEach
    void setUp() {
        for (String t : new String[]{"inquiries", "users"}) {
            jdbc.update("DELETE FROM " + t);
        }
        jdbc.update("INSERT INTO users (mail, pw, uname, nickname, auth) VALUES ('me@example.test', 'x', '김새싹', '새싹', 1)");
        jdbc.update("INSERT INTO users (mail, pw, uname, nickname, auth) VALUES ('other@example.test', 'x', '이타인', '타인', 1)");
        jdbc.update("INSERT INTO users (mail, pw, uname, nickname, auth, status) VALUES ('ban@example.test', 'x', '정지', '정지됨', 1, 'restricted')");
        jdbc.update("INSERT INTO users (mail, pw, uname, nickname, auth) VALUES ('admin@example.test', 'x', '관리자실명', '운영자', 3)");
        me = id("me@example.test");
        other = id("other@example.test");
        banned = id("ban@example.test");
        admin = id("admin@example.test");

        pending1 = inquiry(me, "결제 문의", null, "2026-10-01 10:00:00");
        answered = inquiry(me, "강사 신청 문의", "마이페이지에서 가능합니다.", "2026-10-02 10:00:00");
        pending2 = inquiry(me, "환불 문의", null, "2026-10-03 10:00:00");
        othersQ = inquiry(other, "남의 문의", null, "2026-10-04 10:00:00");
        inquiry(banned, "정지 계정 문의", null, "2026-10-05 10:00:00");
    }

    long id(String mail) {
        return Objects.requireNonNull(jdbc.queryForObject("SELECT user_id FROM users WHERE mail = ?", Long.class, mail));
    }

    long inquiry(long uid, String title, String answer, String created) {
        jdbc.update("INSERT INTO inquiries (user_id, title, content, answer, answered_by, answered_at, created) VALUES (?, ?, '본문', ?, ?, ?, ?)",
                uid, title, answer, answer == null ? null : admin, answer == null ? null : Timestamp.valueOf("2026-10-06 09:00:00"),
                Timestamp.valueOf(created));
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT MAX(iqid) FROM inquiries WHERE user_id = ? AND title = ?", Long.class, uid, title));
    }

    static String bearer(long sub) {
        return "Bearer " + Jwts.builder().subject(String.valueOf(sub)).claim("role", "STUDENT").claim("reissue_num", 2)
                .expiration(new Date(System.currentTimeMillis() + 3_600_000))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)), Jwts.SIG.HS256).compact();
    }

    static String json(String title, String content) {
        return "{\"title\":" + (title == null ? "null" : "\"" + title + "\"")
                + ",\"content\":" + (content == null ? "null" : "\"" + content + "\"") + "}";
    }

    // ------------------------------------------------------------ GET /mypage/cstqna

    @Test @DisplayName("목록 200: 기본 ALL, 최신순, 건수는 전체 기준, 남의 문의 제외")
    void listDefault() throws Exception {
        mvc.perform(get("/mypage/cstqna").header("Authorization", bearer(me)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("CST_QNA_FOUND"))
                .andExpect(jsonPath("$.result.counts.all").value(3))
                .andExpect(jsonPath("$.result.counts.pending").value(2))
                .andExpect(jsonPath("$.result.counts.answered").value(1))
                .andExpect(jsonPath("$.result.inquiries", hasSize(3)))
                .andExpect(jsonPath("$.result.inquiries[0].inquiryId").value(pending2))
                .andExpect(jsonPath("$.result.inquiries[1].inquiryId").value(answered))
                .andExpect(jsonPath("$.result.inquiries[1].status").value("ANSWERED"))
                .andExpect(jsonPath("$.result.inquiries[1].answeredAt", startsWith("2026-10-06T09:00:00")))
                .andExpect(jsonPath("$.result.inquiries[2].status").value("PENDING"))
                .andExpect(jsonPath("$.result.inquiries[2].answeredAt").value(nullValue()))
                .andExpect(jsonPath("$.result.page.number").value(0))
                .andExpect(jsonPath("$.result.page.size").value(5))
                .andExpect(jsonPath("$.result.page.totalElements").value(3))
                .andExpect(jsonPath("$.result.page.hasNext").value(false));
    }

    @Test @DisplayName("목록 200: status=PENDING 필터 + size=1 페이지, 건수는 그대로 전체 기준")
    void listFilterAndPage() throws Exception {
        mvc.perform(get("/mypage/cstqna").param("status", "PENDING").param("size", "1").param("page", "1")
                        .header("Authorization", bearer(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.counts.all").value(3))
                .andExpect(jsonPath("$.result.inquiries", hasSize(1)))
                .andExpect(jsonPath("$.result.inquiries[0].inquiryId").value(pending1))
                .andExpect(jsonPath("$.result.page.totalElements").value(2))
                .andExpect(jsonPath("$.result.page.totalPages").value(2))
                .andExpect(jsonPath("$.result.page.hasNext").value(false));
    }

    @Test @DisplayName("목록 400: 잘못된 status / page / size")
    void listInvalid() throws Exception {
        mvc.perform(get("/mypage/cstqna").param("status", "DONE").param("page", "-1").param("size", "51")
                        .header("Authorization", bearer(me)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.result.errors[*].field", containsInAnyOrder("status", "page", "size")));
    }

    @Test @DisplayName("목록 401: 토큰 없음")
    void listNoToken() throws Exception {
        mvc.perform(get("/mypage/cstqna"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test @DisplayName("목록 200: 제한 계정도 읽기는 가능")
    void listRestrictedCanRead() throws Exception {
        mvc.perform(get("/mypage/cstqna").header("Authorization", bearer(banned)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.counts.all").value(1));
    }

    // ------------------------------------------------------------ GET /mypage/cstqna/{iqid}

    @Test @DisplayName("상세 200: 답변 완료 — answeredBy는 관리자 닉네임만, editable false")
    void detailAnswered() throws Exception {
        mvc.perform(get("/mypage/cstqna/" + answered).header("Authorization", bearer(me)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("CST_QNA_DETAIL_FOUND"))
                .andExpect(jsonPath("$.result.inquiryId").value(answered))
                .andExpect(jsonPath("$.result.content").value("본문"))
                .andExpect(jsonPath("$.result.status").value("ANSWERED"))
                .andExpect(jsonPath("$.result.answer").value("마이페이지에서 가능합니다."))
                .andExpect(jsonPath("$.result.answeredBy.nickname").value("운영자"))
                .andExpect(jsonPath("$.result.answeredBy.uname").doesNotExist())
                .andExpect(jsonPath("$.result.editable").value(false))
                .andExpect(content().string(not(containsString("관리자실명"))));
    }

    @Test @DisplayName("상세 200: 미답변 — answer/answeredBy/answeredAt null, editable true")
    void detailPending() throws Exception {
        mvc.perform(get("/mypage/cstqna/" + pending1).header("Authorization", bearer(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.status").value("PENDING"))
                .andExpect(jsonPath("$.result.answer").value(nullValue()))
                .andExpect(jsonPath("$.result.answeredBy").value(nullValue()))
                .andExpect(jsonPath("$.result.answeredAt").value(nullValue()))
                .andExpect(jsonPath("$.result.editable").value(true));
    }

    @Test @DisplayName("상세 404: 남의 문의, 없는 문의 / 400: iqid 형식 오류")
    void detailNotMine() throws Exception {
        mvc.perform(get("/mypage/cstqna/" + othersQ).header("Authorization", bearer(me)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INQUIRY_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("문의를 찾을 수 없습니다."));
        mvc.perform(get("/mypage/cstqna/999999").header("Authorization", bearer(me)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/mypage/cstqna/abc").header("Authorization", bearer(me)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.result.errors[0].field").value("iqid"));
        mvc.perform(get("/mypage/cstqna/0").header("Authorization", bearer(me)))
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------ POST /mypage/cstqna/posts

    @Test @DisplayName("작성 201: 앞뒤 공백 제거, 작성자는 토큰 sub, 상세 모양 응답")
    void createOk() throws Exception {
        mvc.perform(post("/mypage/cstqna/posts").header("Authorization", bearer(me))
                        .contentType(MediaType.APPLICATION_JSON).content(json("  새 문의  ", " 내용입니다 ")))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", startsWith("/mypage/cstqna/")))
                .andExpect(jsonPath("$.code").value("CST_QNA_CREATED"))
                .andExpect(jsonPath("$.result.title").value("새 문의"))
                .andExpect(jsonPath("$.result.content").value("내용입니다"))
                .andExpect(jsonPath("$.result.status").value("PENDING"))
                .andExpect(jsonPath("$.result.answer").value(nullValue()))
                .andExpect(jsonPath("$.result.updatedAt").value(nullValue()))
                .andExpect(jsonPath("$.result.editable").value(true));
        Long owner = jdbc.queryForObject("SELECT user_id FROM inquiries WHERE title = '새 문의'", Long.class);
        org.junit.jupiter.api.Assertions.assertEquals(me, owner);
    }

    @Test @DisplayName("작성 400: 빈 제목·내용, 200/4000자 초과")
    void createInvalid() throws Exception {
        mvc.perform(post("/mypage/cstqna/posts").header("Authorization", bearer(me))
                        .contentType(MediaType.APPLICATION_JSON).content(json("   ", null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.result.errors[*].field", containsInAnyOrder("title", "content")));
        mvc.perform(post("/mypage/cstqna/posts").header("Authorization", bearer(me))
                        .contentType(MediaType.APPLICATION_JSON).content(json("가".repeat(201), "나".repeat(4001))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.result.errors[*].field", containsInAnyOrder("title", "content")));
    }

    @Test @DisplayName("작성 403: 제한 계정 (토큰이 남아 있어도 DB status로 차단)")
    void createRestricted() throws Exception {
        mvc.perform(post("/mypage/cstqna/posts").header("Authorization", bearer(banned))
                        .contentType(MediaType.APPLICATION_JSON).content(json("제목", "내용")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCOUNT_RESTRICTED"));
    }

    @Test @DisplayName("작성 401: 토큰은 맞지만 회원이 없음")
    void createUnknownMember() throws Exception {
        mvc.perform(post("/mypage/cstqna/posts").header("Authorization", bearer(987654))
                        .contentType(MediaType.APPLICATION_JSON).content(json("제목", "내용")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    // ------------------------------------------------------------ PUT /mypage/cstqna/{iqid}

    @Test @DisplayName("수정 200: 미답변 내 문의, updatedAt 채워짐")
    void updateOk() throws Exception {
        mvc.perform(put("/mypage/cstqna/" + pending1).header("Authorization", bearer(me))
                        .contentType(MediaType.APPLICATION_JSON).content(json("고친 제목", "고친 내용")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("CST_QNA_UPDATED"))
                .andExpect(jsonPath("$.result.title").value("고친 제목"))
                .andExpect(jsonPath("$.result.content").value("고친 내용"))
                .andExpect(jsonPath("$.result.updatedAt").value(notNullValue()));
    }

    @Test @DisplayName("수정 409: 답변 달린 문의 / 404: 남의 문의 / 403: 제한 계정 / 400: 빈 입력")
    void updateRejected() throws Exception {
        mvc.perform(put("/mypage/cstqna/" + answered).header("Authorization", bearer(me))
                        .contentType(MediaType.APPLICATION_JSON).content(json("a", "b")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INQUIRY_ALREADY_ANSWERED"))
                .andExpect(jsonPath("$.message").value("답변이 달린 문의는 수정할 수 없습니다."));
        mvc.perform(put("/mypage/cstqna/" + othersQ).header("Authorization", bearer(me))
                        .contentType(MediaType.APPLICATION_JSON).content(json("a", "b")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INQUIRY_NOT_FOUND"));
        mvc.perform(put("/mypage/cstqna/" + pending1).header("Authorization", bearer(banned))
                        .contentType(MediaType.APPLICATION_JSON).content(json("a", "b")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCOUNT_RESTRICTED"));
        mvc.perform(put("/mypage/cstqna/" + pending1).header("Authorization", bearer(me))
                        .contentType(MediaType.APPLICATION_JSON).content(json("", "")))
                .andExpect(status().isBadRequest());
        String title = jdbc.queryForObject("SELECT title FROM inquiries WHERE iqid = ?", String.class, othersQ);
        org.junit.jupiter.api.Assertions.assertEquals("남의 문의", title);
    }

    // ------------------------------------------------------------ DELETE /mypage/cstqna/{iqid}

    @Test @DisplayName("삭제 200: 미답변 내 문의는 실제 삭제")
    void deleteOk() throws Exception {
        mvc.perform(delete("/mypage/cstqna/" + pending2).header("Authorization", bearer(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("CST_QNA_DELETED"))
                .andExpect(jsonPath("$.result.inquiryId").value(pending2));
        Long left = jdbc.queryForObject("SELECT COUNT(*) FROM inquiries WHERE iqid = ?", Long.class, pending2);
        org.junit.jupiter.api.Assertions.assertEquals(0L, left);
    }

    @Test @DisplayName("삭제 409: 답변 달린 문의 / 404: 남의 문의 / 403: 제한 계정 — 행은 그대로")
    void deleteRejected() throws Exception {
        mvc.perform(delete("/mypage/cstqna/" + answered).header("Authorization", bearer(me)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("답변이 달린 문의는 삭제할 수 없습니다."));
        mvc.perform(delete("/mypage/cstqna/" + othersQ).header("Authorization", bearer(me)))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/mypage/cstqna/" + pending1).header("Authorization", bearer(banned)))
                .andExpect(status().isForbidden());
        Long left = jdbc.queryForObject("SELECT COUNT(*) FROM inquiries WHERE iqid IN (?, ?, ?)", Long.class,
                answered, othersQ, pending1);
        org.junit.jupiter.api.Assertions.assertEquals(3L, left);
    }
}
