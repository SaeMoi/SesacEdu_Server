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
import java.util.Map;
import java.util.Objects;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * auth 패키지의 login / login_replc / logout 이 Notion 명세(로그인, JWT 재발급, 로그아웃)대로 응답하는지 확인.
 * 명세의 응답 코드를 모두 확인한다:
 *   POST /login       200 LOGIN_SUCCESS / 400 INVALID_INPUT / 401 LOGIN_FAILED / 403 ACCOUNT_RESTRICTED
 *   POST /login/replc 200 REISSUE_SUCCESS / 400 TOKEN_NOT_EXPIRED / 401 UNAUTHORIZED·REISSUE_LIMIT·REISSUE_EXPIRED
 *                     / 403 ACCOUNT_RESTRICTED
 *   POST /logout      204 / 401 UNAUTHORIZED
 * H2(Oracle 모드, application-test.yml + schema-test.sql)로 실행:
 *   gradlew test --tests com.example.sesacedu.LoginApiTest
 */
@SuppressWarnings("null") // VS Code(JDT) null 분석이 Spring/MockMvc API에 내는 경고 제외. 실행에는 영향 없음
@SpringBootTest(properties = "JWT_SECRET=" + LoginApiTest.SECRET)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LoginApiTest {

    static final String SECRET = "login-api-test-secret-at-least-32-bytes!!";
    static final String PW = "Passw0rd!";

    /** 명세 응답 예시의 message (code → message) */
    static final Map<String, String> MESSAGES = Map.of(
            "UNAUTHORIZED", "인증 정보가 올바르지 않습니다.",
            "TOKEN_NOT_EXPIRED", "아직 만료되지 않은 토큰입니다.",
            "REISSUE_LIMIT", "로그인 시간이 끝났습니다. 다시 로그인해주세요.",
            "REISSUE_EXPIRED", "로그인이 만료되었습니다. 다시 로그인해주세요.",
            "ACCOUNT_RESTRICTED", "이용이 제한된 계정입니다.");

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    long studentId;
    long restrictedId;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM users");
        String md5 = DigestUtils.md5DigestAsHex(PW.getBytes(StandardCharsets.UTF_8));
        jdbc.update("INSERT INTO users (mail, pw, uname, nickname, auth) VALUES (?, ?, ?, ?, 1)",
                "jade@example.com", md5, "김태형", "태형");
        jdbc.update("INSERT INTO users (mail, pw, uname, nickname, auth, status) VALUES (?, ?, ?, ?, 1, 'restricted')",
                "ban@example.com", md5, "제한", "제한");
        studentId = Objects.requireNonNull(jdbc.queryForObject("SELECT user_id FROM users WHERE mail = 'jade@example.com'", Long.class));
        restrictedId = Objects.requireNonNull(jdbc.queryForObject("SELECT user_id FROM users WHERE mail = 'ban@example.com'", Long.class));
    }

    static String json(String email, String pw) {
        return "{\"email\":" + (email == null ? "null" : "\"" + email + "\"")
                + ",\"pw\":" + (pw == null ? "null" : "\"" + pw + "\"") + "}";
    }

    /** exp·reissue_num을 마음대로 정한 토큰. secondsAgo > 0 이면 이미 만료 */
    static String token(long sub, long secondsAgo, Integer reissueNum, String secret) {
        var b = Jwts.builder().subject(String.valueOf(sub)).claim("role", "STUDENT")
                .expiration(new Date(System.currentTimeMillis() - secondsAgo * 1000));
        if (reissueNum != null) b.claim("reissue_num", reissueNum);
        return b.signWith(Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8)), Jwts.SIG.HS256).compact();
    }

    static String token(long sub, long secondsAgo, Integer reissueNum) {
        return token(sub, secondsAgo, reissueNum, SECRET);
    }

    /** 서명 없는 토큰 (alg: none). secondsAgo > 0 이면 이미 만료 */
    static String unsignedToken(long sub, long secondsAgo) {
        return Jwts.builder().subject(String.valueOf(sub)).claim("role", "STUDENT").claim("reissue_num", 2)
                .expiration(new Date(System.currentTimeMillis() - secondsAgo * 1000)).compact();
    }

    /** 서명은 맞지만 로그인 토큰이 아닌 토큰: sub가 숫자가 아님 / reauth 토큰(typ 클레임) */
    static String oddToken(String sub, String typ, long secondsAgo) {
        var b = Jwts.builder().subject(sub).claim("role", "STUDENT").claim("reissue_num", 2)
                .expiration(new Date(System.currentTimeMillis() - secondsAgo * 1000));
        if (typ != null) b.claim("typ", typ).claim("purpose", "PROFILE_UPDATE");
        return b.signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)), Jwts.SIG.HS256).compact();
    }

    static Claims claimsOf(String headerValue) {
        return Jwts.parser().verifyWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8))).build()
                .parseSignedClaims(headerValue.substring("Bearer ".length())).getPayload();
    }

    // ------------------------------------------------------------ POST /login

    @Test @DisplayName("로그인 200: 토큰은 Authorization 헤더에만, payload sub/role/reissue_num 2, name 없음, last_login 갱신")
    void loginSuccess() throws Exception {
        MvcResult r = mvc.perform(post("/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json("  Jade@Example.COM ", PW)))      // 공백 제거 + 소문자 변환
                .andExpect(status().isOk())
                .andExpect(header().string("Authorization", startsWith("Bearer ")))
                .andExpect(header().string("Access-Control-Expose-Headers", "Authorization"))
                .andExpect(jsonPath("$.code").value("LOGIN_SUCCESS"))
                .andExpect(jsonPath("$.message").value("로그인되었습니다."))
                .andExpect(jsonPath("$.result.member.memberId").value(studentId))
                .andExpect(jsonPath("$.result.member.name").value("김태형"))
                .andExpect(jsonPath("$.result.member.nickname").value("태형"))
                .andExpect(jsonPath("$.result.member.role").value("STUDENT"))
                .andExpect(jsonPath("$.result.accessToken").doesNotExist())
                .andReturn();
        Claims c = claimsOf(r.getResponse().getHeader("Authorization"));
        assertEquals(String.valueOf(studentId), c.getSubject());
        assertEquals("STUDENT", c.get("role"));
        assertEquals(2, ((Number) c.get("reissue_num")).intValue());
        assertNull(c.get("name"));
        assertNotNull(jdbc.queryForObject("SELECT last_login_ip FROM users WHERE user_id = ?", String.class, studentId));
    }

    @Test @DisplayName("로그인 401 LOGIN_FAILED: 비밀번호 틀림과 없는 이메일이 같은 응답")
    void loginFailed() throws Exception {
        for (String body : new String[]{json("jade@example.com", "wrong"), json("nobody@example.com", PW)}) {
            mvc.perform(post("/login").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().doesNotExist("Authorization"))
                    .andExpect(jsonPath("$.code").value("LOGIN_FAILED"))
                    .andExpect(jsonPath("$.message").value("이메일 또는 비밀번호를 확인해주세요."))
                    .andExpect(jsonPath("$.result").value(nullValue()));
        }
    }

    @Test @DisplayName("로그인 400 INVALID_INPUT: 누락·형식 오류·이메일 101자·비밀번호 31자·깨진 JSON·본문 없음, 토큰 없음")
    void loginInvalidInput() throws Exception {
        mvc.perform(post("/login").contentType(MediaType.APPLICATION_JSON).content(json("not-an-email", null)))
                .andExpect(status().isBadRequest())
                .andExpect(header().doesNotExist("Authorization"))
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.message").value("입력값을 확인해주세요."))
                .andExpect(jsonPath("$.result.errors[*].field", containsInAnyOrder("email", "pw")))
                .andExpect(jsonPath("$.result.errors[*].reason", everyItem(not(emptyString()))));
        String longEmail = "a".repeat(89) + "@example.com";             // 101자
        mvc.perform(post("/login").contentType(MediaType.APPLICATION_JSON).content(json(longEmail, "x".repeat(31))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.result.errors[*].field", containsInAnyOrder("email", "pw")));
        mvc.perform(post("/login").contentType(MediaType.APPLICATION_JSON).content(json("", "")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.result.errors[*].field", containsInAnyOrder("email", "pw")));
        mvc.perform(post("/login").contentType(MediaType.APPLICATION_JSON).content("{broken"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        mvc.perform(post("/login").contentType(MediaType.APPLICATION_JSON))   // 본문 없음
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    @Test @DisplayName("로그인 경계값: 이메일 100자·비밀번호 30자는 통과 (검증 뒤 401 LOGIN_FAILED)")
    void loginBoundary() throws Exception {
        String email100 = "a".repeat(88) + "@example.com";              // 100자
        mvc.perform(post("/login").contentType(MediaType.APPLICATION_JSON).content(json(email100, "x".repeat(30))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("LOGIN_FAILED"));
    }

    @Test @DisplayName("로그인 403 ACCOUNT_RESTRICTED: 비밀번호가 맞을 때만, 토큰 없음. 틀리면 401")
    void loginRestricted() throws Exception {
        mvc.perform(post("/login").contentType(MediaType.APPLICATION_JSON).content(json("ban@example.com", PW)))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Authorization"))
                .andExpect(jsonPath("$.code").value("ACCOUNT_RESTRICTED"))
                .andExpect(jsonPath("$.message").value("이용이 제한된 계정입니다."))
                .andExpect(jsonPath("$.result").value(nullValue()));
        mvc.perform(post("/login").contentType(MediaType.APPLICATION_JSON).content(json("ban@example.com", "wrong")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("LOGIN_FAILED"));
        assertNull(jdbc.queryForObject("SELECT last_login_ip FROM users WHERE user_id = ?", String.class, restrictedId));
    }

    @Test @DisplayName("로그인 잠금 없음 (의도된 취약점): 20번 틀려도 맞는 비밀번호면 200")
    void noLockout() throws Exception {
        for (int i = 0; i < 20; i++) {
            mvc.perform(post("/login").contentType(MediaType.APPLICATION_JSON).content(json("jade@example.com", "x" + i)))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/login").contentType(MediaType.APPLICATION_JSON).content(json("jade@example.com", PW)))
                .andExpect(status().isOk());
    }

    // ------------------------------------------------------------ POST /login/replc

    @Test @DisplayName("재발급 200: reissue_num 2 → 1, role은 DB 값, 헤더만으로 전달")
    void reissueSuccess() throws Exception {
        jdbc.update("UPDATE users SET auth = 2 WHERE user_id = ?", studentId);   // 강사 승인이 재발급에 반영되는지
        MvcResult r = mvc.perform(post("/login/replc").header("Authorization", "Bearer " + token(studentId, 60, 2)))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Expose-Headers", "Authorization"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("REISSUE_SUCCESS"))
                .andExpect(jsonPath("$.message").value("로그인이 연장되었습니다."))
                .andExpect(jsonPath("$.result.reissueNum").value(1))
                .andReturn();
        Claims c = claimsOf(r.getResponse().getHeader("Authorization"));
        assertEquals(String.valueOf(studentId), c.getSubject());
        assertEquals("INSTRUCTOR", c.get("role"));
        assertEquals(1, ((Number) c.get("reissue_num")).intValue());
        assertTrue(c.getExpiration().after(new Date()));
    }

    @Test @DisplayName("재발급 오류 코드: TOKEN_NOT_EXPIRED / REISSUE_LIMIT / REISSUE_EXPIRED / UNAUTHORIZED / ACCOUNT_RESTRICTED")
    void reissueErrors() throws Exception {
        expect("/login/replc", "Bearer " + token(studentId, -600, 2), 400, "TOKEN_NOT_EXPIRED");
        expect("/login/replc", "Bearer " + token(studentId, 60, 0), 401, "REISSUE_LIMIT");
        expect("/login/replc", "Bearer " + token(studentId, 60, null), 401, "REISSUE_LIMIT");
        expect("/login/replc", "Bearer " + token(studentId, 86400 + 60, 2), 401, "REISSUE_EXPIRED");
        expect("/login/replc", null, 401, "UNAUTHORIZED");
        expect("/login/replc", "Bearer " + token(studentId, 60, 2, "another-secret-another-secret-32bytes!!"), 401, "UNAUTHORIZED");
        expect("/login/replc", "Bearer " + token(999999, 60, 2), 401, "UNAUTHORIZED");
        expect("/login/replc", "Bearer " + token(restrictedId, 60, 2), 403, "ACCOUNT_RESTRICTED");
    }

    @Test @DisplayName("재발급 401 UNAUTHORIZED: Bearer 아님, alg none(서명 없음), sub 숫자 아님, reauth 토큰")
    void reissueBadTokens() throws Exception {
        expect("/login/replc", "Basic abc", 401, "UNAUTHORIZED");
        expect("/login/replc", "Bearer ", 401, "UNAUTHORIZED");
        expect("/login/replc", "Bearer not.a.jwt", 401, "UNAUTHORIZED");
        expect("/login/replc", "Bearer " + unsignedToken(studentId, 60), 401, "UNAUTHORIZED");
        expect("/login/replc", "Bearer " + oddToken("abc", null, 60), 401, "UNAUTHORIZED");
        expect("/login/replc", "Bearer " + oddToken(String.valueOf(studentId), "reauth", 60), 401, "UNAUTHORIZED");
    }

    @Test @DisplayName("재발급 연속: 2 → 1 → 0, 0인 토큰은 REISSUE_LIMIT (다시 로그인)")
    void reissueChain() throws Exception {
        expectReissueNum(token(studentId, 60, 2), 1);
        expectReissueNum(token(studentId, 60, 1), 0);
        expect("/login/replc", "Bearer " + token(studentId, 60, 0), 401, "REISSUE_LIMIT");
    }

    private void expectReissueNum(String expiredToken, int next) throws Exception {
        MvcResult r = mvc.perform(post("/login/replc").header("Authorization", "Bearer " + expiredToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.reissueNum").value(next))
                .andReturn();
        assertEquals(next, ((Number) claimsOf(r.getResponse().getHeader("Authorization")).get("reissue_num")).intValue());
    }

    @Test @DisplayName("재발급 횟수 우회 (의도된 취약점): 처음 토큰을 다시 보내면 매번 200")
    void reissueReplay() throws Exception {
        String first = "Bearer " + token(studentId, 60, 2);
        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/login/replc").header("Authorization", first))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.result.reissueNum").value(1));
        }
    }

    // ------------------------------------------------------------ POST /logout

    @Test @DisplayName("로그아웃 204: 유효·만료 토큰 모두, 다시 보내도 204, 토큰은 계속 유효 (의도된 취약점)")
    void logout() throws Exception {
        String valid = "Bearer " + token(studentId, -600, 2);
        for (String t : new String[]{valid, valid, "Bearer " + token(studentId, 60 * 60 * 48, 0)}) {
            mvc.perform(post("/logout").header("Authorization", t))
                    .andExpect(status().isNoContent())
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(content().string(""));
        }
        // 로그아웃한 토큰도 만료 전이라 재발급 API에서 그대로 "아직 유효" 판정
        expect("/login/replc", valid, 400, "TOKEN_NOT_EXPIRED");
    }

    @Test @DisplayName("로그아웃 401 UNAUTHORIZED: 토큰 없음·Bearer 아님·위조")
    void logoutUnauthorized() throws Exception {
        expect("/logout", null, 401, "UNAUTHORIZED");
        expect("/logout", "Basic abc", 401, "UNAUTHORIZED");
        expect("/logout", "Bearer " + token(studentId, -600, 2, "another-secret-another-secret-32bytes!!"), 401, "UNAUTHORIZED");
        expect("/logout", "Bearer not.a.jwt", 401, "UNAUTHORIZED");
        expect("/logout", "Bearer " + unsignedToken(studentId, -600), 401, "UNAUTHORIZED");      // alg none
        expect("/logout", "Bearer " + oddToken(String.valueOf(studentId), "reauth", -600), 401, "UNAUTHORIZED");
    }

    private void expect(String path, String authorization, int status, String code) throws Exception {
        var req = post(path);
        if (authorization != null) req.header("Authorization", authorization);
        mvc.perform(req)
                .andExpect(status().is(status))
                .andExpect(header().doesNotExist("Authorization"))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.message").value(MESSAGES.get(code)))
                .andExpect(jsonPath("$.result").value(nullValue()));
    }
}
