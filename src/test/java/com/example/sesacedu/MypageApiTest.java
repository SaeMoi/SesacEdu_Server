package com.example.sesacedu;

import io.jsonwebtoken.Claims;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Objects;

import com.jayway.jsonpath.JsonPath;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * mypage 패키지의 mypage / verify_password 가 Notion 명세(마이 페이지, 현재 비밀번호 확인)대로 응답하는지 확인.
 * H2(Oracle 모드, application-test.yml + schema-test.sql)로 실행:
 *   gradlew test --tests com.example.sesacedu.MypageApiTest
 */
@SuppressWarnings("null") // VS Code(JDT) null 분석이 Spring/MockMvc API에 내는 경고 제외. 실행에는 영향 없음
@SpringBootTest(properties = "JWT_SECRET=" + MypageApiTest.SECRET)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MypageApiTest {

    static final String SECRET = "mypage-api-test-secret-at-least-32-bytes!!";
    static final String PW = "Passw0rd!";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    long studentId;
    long bareId;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM users");
        String md5 = DigestUtils.md5DigestAsHex(PW.getBytes(StandardCharsets.UTF_8));
        jdbc.update("INSERT INTO users (mail, pw, uname, nickname, phone, b_date, address, auth, profile_image, created) "
                        + "VALUES (?, ?, ?, ?, ?, DATE '2000-03-15', ?, 1, ?, TIMESTAMP '2026-10-01 09:00:00')",
                "student@example.test", md5, "김새싹", "새싹보안", "01012345678", "서울시 어딘가", "15_profile.png");
        jdbc.update("INSERT INTO users (mail, pw, uname, nickname, auth) VALUES (?, ?, ?, ?, 0)",
                "bare@example.test", md5, "빈칸", "빈칸");
        studentId = Objects.requireNonNull(jdbc.queryForObject("SELECT user_id FROM users WHERE mail = 'student@example.test'", Long.class));
        bareId = Objects.requireNonNull(jdbc.queryForObject("SELECT user_id FROM users WHERE mail = 'bare@example.test'", Long.class));
    }

    /** 로그인 토큰. secondsAgo > 0 이면 이미 만료 */
    static String token(long sub, long secondsAgo, String secret) {
        return Jwts.builder().subject(String.valueOf(sub)).claim("role", "STUDENT").claim("reissue_num", 2)
                .expiration(new Date(System.currentTimeMillis() - secondsAgo * 1000))
                .signWith(Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8)), Jwts.SIG.HS256).compact();
    }

    static String bearer(long sub) {
        return "Bearer " + token(sub, -3600, SECRET);
    }

    static String json(String pw, String purpose) {
        return "{\"pw\":" + (pw == null ? "null" : "\"" + pw + "\"")
                + ",\"purpose\":" + (purpose == null ? "null" : "\"" + purpose + "\"") + "}";
    }

    static Claims claimsOf(String jwt) {
        return Jwts.parser().verifyWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8))).build()
                .parseSignedClaims(jwt).getPayload();
    }

    // ------------------------------------------------------------ GET /mypage

    @Test @DisplayName("마이페이지 200: 명세 필드만, 사진 URL 조립, role 변환, no-store, pw·address 없음")
    void mypageSuccess() throws Exception {
        mvc.perform(get("/mypage").header("Authorization", bearer(studentId)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("PROFILE_FOUND"))
                .andExpect(jsonPath("$.message").value("프로필을 조회했습니다."))
                .andExpect(jsonPath("$.result.memberId").value(studentId))
                .andExpect(jsonPath("$.result.name").value("김새싹"))
                .andExpect(jsonPath("$.result.email").value("student@example.test"))
                .andExpect(jsonPath("$.result.nickname").value("새싹보안"))
                .andExpect(jsonPath("$.result.phone").value("01012345678"))
                .andExpect(jsonPath("$.result.birthDate").value("2000-03-15"))
                .andExpect(jsonPath("$.result.profileImageUrl").value("/images/profile/15_profile.png"))
                .andExpect(jsonPath("$.result.role").value("STUDENT"))
                .andExpect(jsonPath("$.result.createdAt", matchesPattern("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\+09:00")))
                .andExpect(jsonPath("$.result.pw").doesNotExist())
                .andExpect(jsonPath("$.result.address").doesNotExist())
                .andExpect(jsonPath("$.result.bio").doesNotExist())
                .andExpect(jsonPath("$.result.length()").value(9));
    }

    @Test @DisplayName("마이페이지 200: phone·birthDate·profileImageUrl 없으면 null, auth 0 → GENERAL")
    void mypageNulls() throws Exception {
        mvc.perform(get("/mypage").header("Authorization", bearer(bareId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.phone").value(nullValue()))
                .andExpect(jsonPath("$.result.birthDate").value(nullValue()))
                .andExpect(jsonPath("$.result.profileImageUrl").value(nullValue()))
                .andExpect(jsonPath("$.result.role").value("GENERAL"));
    }

    @Test @DisplayName("마이페이지 401: 토큰 없음·위조는 UNAUTHORIZED, 만료는 TOKEN_EXPIRED, reauth 토큰은 거부")
    void mypageUnauthorized() throws Exception {
        mvc.perform(get("/mypage"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.result").value(nullValue()));
        mvc.perform(get("/mypage").header("Authorization", "Bearer " + token(studentId, -3600, "another-secret-another-secret-another!!")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mvc.perform(get("/mypage").header("Authorization", "Bearer " + token(studentId, 60, SECRET)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TOKEN_EXPIRED"));
        String reauth = reauthToken(studentId, "PROFILE_UPDATE");
        mvc.perform(get("/mypage").header("Authorization", "Bearer " + reauth))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test @DisplayName("마이페이지 404 MEMBER_NOT_FOUND: 서명은 맞지만 회원이 없음")
    void mypageNotFound() throws Exception {
        mvc.perform(get("/mypage").header("Authorization", bearer(999999)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MEMBER_NOT_FOUND"))
                .andExpect(jsonPath("$.result").value(nullValue()));
    }

    @Test @DisplayName("마이페이지: 조회 대상은 토큰 sub로만 (쿼리로 다른 ID를 보내도 무시)")
    void mypageIgnoresIdParam() throws Exception {
        mvc.perform(get("/mypage").param("memberId", String.valueOf(studentId)).header("Authorization", bearer(bareId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.memberId").value(bareId));
    }

    // ------------------------------------------------------------ POST /mypage/verify-password

    String reauthToken(long sub, String purpose) throws Exception {
        MvcResult r = mvc.perform(post("/mypage/verify-password").header("Authorization", bearer(sub))
                        .contentType(MediaType.APPLICATION_JSON).content(json(PW, purpose)))
                .andExpect(status().isOk()).andReturn();
        return JsonPath.read(r.getResponse().getContentAsString(), "$.result.reauthToken");
    }

    @Test @DisplayName("본인 확인 200: reauth JWT {sub, typ:reauth, purpose, exp 10분}, expiresIn 600, no-store")
    void verifySuccess() throws Exception {
        for (String purpose : new String[]{"PROFILE_UPDATE", "PASSWORD_CHANGE"}) {
            MvcResult r = mvc.perform(post("/mypage/verify-password").header("Authorization", bearer(studentId))
                            .contentType(MediaType.APPLICATION_JSON).content(json(PW, purpose)))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.code").value("PASSWORD_VERIFIED"))
                    .andExpect(jsonPath("$.message").value("본인 확인이 완료되었습니다."))
                    .andExpect(jsonPath("$.result.expiresIn").value(600))
                    .andReturn();
            Claims c = claimsOf(JsonPath.read(r.getResponse().getContentAsString(), "$.result.reauthToken"));
            assertEquals(String.valueOf(studentId), c.getSubject());
            assertEquals("reauth", c.get("typ"));
            assertEquals(purpose, c.get("purpose"));
            assertNull(c.get("role"));
            long ttl = (c.getExpiration().getTime() - System.currentTimeMillis()) / 1000;
            assertTrue(ttl > 590 && ttl <= 600, "ttl=" + ttl);
        }
    }

    @Test @DisplayName("본인 확인 401 PASSWORD_MISMATCH: result null, 토큰 없음")
    void verifyMismatch() throws Exception {
        mvc.perform(post("/mypage/verify-password").header("Authorization", bearer(studentId))
                        .contentType(MediaType.APPLICATION_JSON).content(json("wrong", "PROFILE_UPDATE")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("PASSWORD_MISMATCH"))
                .andExpect(jsonPath("$.message").value("비밀번호가 올바르지 않습니다."))
                .andExpect(jsonPath("$.result").value(nullValue()));
    }

    @Test @DisplayName("본인 확인 횟수 제한 없음 (의도된 취약점): 20번 틀려도 맞으면 200")
    void verifyNoLockout() throws Exception {
        for (int i = 0; i < 20; i++) {
            mvc.perform(post("/mypage/verify-password").header("Authorization", bearer(studentId))
                            .contentType(MediaType.APPLICATION_JSON).content(json("x" + i, "PROFILE_UPDATE")))
                    .andExpect(status().isUnauthorized());
        }
        reauthToken(studentId, "PROFILE_UPDATE");
    }

    @Test @DisplayName("본인 확인 400 INVALID_INPUT: pw 누락·31자, purpose 오류, 깨진 JSON")
    void verifyInvalidInput() throws Exception {
        mvc.perform(post("/mypage/verify-password").header("Authorization", bearer(studentId))
                        .contentType(MediaType.APPLICATION_JSON).content(json(null, "DELETE_ACCOUNT")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.result.errors[*].field", containsInAnyOrder("pw", "purpose")));
        mvc.perform(post("/mypage/verify-password").header("Authorization", bearer(studentId))
                        .contentType(MediaType.APPLICATION_JSON).content(json("a".repeat(31), "PASSWORD_CHANGE")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.result.errors[0].field").value("pw"));
        mvc.perform(post("/mypage/verify-password").header("Authorization", bearer(studentId))
                        .contentType(MediaType.APPLICATION_JSON).content("{broken"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    @Test @DisplayName("본인 확인 401: 토큰 없음 UNAUTHORIZED (본문이 깨져도), 만료 TOKEN_EXPIRED")
    void verifyUnauthorized() throws Exception {
        mvc.perform(post("/mypage/verify-password").contentType(MediaType.APPLICATION_JSON).content(json(PW, "PROFILE_UPDATE")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mvc.perform(post("/mypage/verify-password").contentType(MediaType.APPLICATION_JSON).content("{broken"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mvc.perform(post("/mypage/verify-password").header("Authorization", "Bearer " + token(studentId, 60, SECRET))
                        .contentType(MediaType.APPLICATION_JSON).content(json(PW, "PROFILE_UPDATE")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TOKEN_EXPIRED"));
    }

    @Test @DisplayName("본인 확인: 다른 회원 비밀번호로는 확인 불가 (회원은 토큰 sub로만)")
    void verifyUsesTokenSubOnly() throws Exception {
        jdbc.update("UPDATE users SET pw = ? WHERE user_id = ?",
                DigestUtils.md5DigestAsHex("Other0ne!".getBytes(StandardCharsets.UTF_8)), bareId);
        mvc.perform(post("/mypage/verify-password").header("Authorization", bearer(studentId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pw\":\"Other0ne!\",\"purpose\":\"PROFILE_UPDATE\",\"memberId\":" + bareId + "}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("PASSWORD_MISMATCH"));
    }
}
