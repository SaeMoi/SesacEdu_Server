package com.example.sesacedu.global;

import com.example.sesacedu.global.JwtUtil.JwtAuthException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JwtUtil이 Notion API 명세(로그인 / JWT 재발급 / 로그아웃 / 현재 비밀번호 확인)대로 동작하는지 확인.
 * Spring·DB 없이 실행: gradlew test --tests com.example.sesacedu.global.JwtUtilTest
 */
class JwtUtilTest {

    static final String SECRET = "test-secret-test-secret-test-secret!!";
    final JwtUtil jwt = new JwtUtil(SECRET, 3600, 2, 86400);

    /** 예외가 나면 "상태 코드", 아니면 "OK" */
    static String result(Runnable r) {
        try { r.run(); return "OK"; }
        catch (JwtAuthException e) { return e.getStatus() + " " + e.getCode(); }
    }

    /** exp를 마음대로 정한 토큰 (만료 상황 재현용). secondsAgo > 0 이면 이미 만료 */
    static String tokenExpired(long secondsAgo, Integer reissueNum) {
        var b = Jwts.builder().subject("15").claim("role", "STUDENT")
                .expiration(new Date(System.currentTimeMillis() - secondsAgo * 1000));
        if (reissueNum != null) b.claim("reissue_num", reissueNum);
        return b.signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)), Jwts.SIG.HS256).compact();
    }

    static String payloadJson(String token) {
        return new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]), StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------ 로그인 (POST /login)

    @Test @DisplayName("로그인 토큰: HS256, payload = sub/role/reissue_num(2)/exp(+60분)")
    void loginToken() {
        String t = jwt.createLoginToken(15, 1);
        assertTrue(new String(Base64.getUrlDecoder().decode(t.split("\\.")[0])).contains("\"HS256\""));
        Claims c = jwt.parse(t);
        assertEquals(15L, jwt.getUid(c));
        assertFalse(payloadJson(t).contains("\"name\""), "payload에 name 없음");
        assertEquals("STUDENT", jwt.getRole(c));
        assertEquals(2, jwt.getReissueNum(c));
        long ttl = c.getExpiration().getTime() / 1000 - System.currentTimeMillis() / 1000;
        assertTrue(ttl > 3590 && ttl <= 3600, "exp ≈ 지금 + 3600초");
        System.out.println("login payload = " + payloadJson(t));
    }

    @Test @DisplayName("users.auth 0~4 → GENERAL/STUDENT/INSTRUCTOR/ADMIN/INSTRUCTOR_PENDING")
    void roleCodes() {
        String[] expected = {"GENERAL", "STUDENT", "INSTRUCTOR", "ADMIN", "INSTRUCTOR_PENDING"};
        for (int auth = 0; auth < 5; auth++) assertEquals(expected[auth], JwtUtil.roleOf(auth));
    }

    // ------------------------------------------------------------ 모든 인증 API 공통 (JwtFilter)

    @Test @DisplayName("공통: 없음·위조·HS256 아님 → 401 UNAUTHORIZED, 만료 → 401 TOKEN_EXPIRED, 권한 부족 → 403 FORBIDDEN")
    void filterRules() {
        String t = jwt.createLoginToken(15, 1);
        assertEquals("401 UNAUTHORIZED", result(() -> jwt.parse((String) null)));
        assertEquals("401 UNAUTHORIZED", result(() -> jwt.parse(t.substring(0, t.length() - 2) + "xx")));
        assertEquals("401 UNAUTHORIZED", result(() -> new JwtUtil("other-secret-other-secret-other-secret", 3600, 2, 86400).parse(t)));
        String hs512 = Jwts.builder().subject("15").claim("role", "ADMIN")
                .signWith(Keys.hmacShaKeyFor((SECRET + SECRET).getBytes(StandardCharsets.UTF_8)), Jwts.SIG.HS512).compact();
        assertEquals("401 UNAUTHORIZED", result(() -> new JwtUtil(SECRET + SECRET, 3600, 2, 86400).parse(hs512)));
        assertEquals("401 TOKEN_EXPIRED", result(() -> jwt.parse(tokenExpired(60, 2))));
        assertEquals("403 FORBIDDEN", result(() -> jwt.requireRole(jwt.parse(t), "ADMIN")));
        assertEquals("OK", result(() -> jwt.requireRole(jwt.parse(t), "STUDENT", "ADMIN")));
    }

    @Test @DisplayName("Authorization 헤더: 'Bearer xxx'만 인정")
    void bearerHeader() {
        assertEquals("abc", jwt.resolveToken("Bearer abc"));
        assertNull(jwt.resolveToken("abc"));
        assertNull(jwt.resolveToken("Basic abc"));
        assertNull(jwt.resolveToken((String) null));
    }

    // ------------------------------------------------------------ JWT 재발급 (POST /login/replc)

    @Test @DisplayName("재발급: 만료 토큰 → reissue_num-1, role은 DB 값으로 갱신, 60분")
    void reissue() {
        Claims old = jwt.parseForReissue(tokenExpired(60, 2));
        String fresh = jwt.reissue(old, 2);   // DB에서 강사 승인된 상태라고 가정
        Claims c = jwt.parse(fresh);
        assertEquals(15L, jwt.getUid(c));
        assertEquals(1, jwt.getReissueNum(c));
        assertEquals("INSTRUCTOR", jwt.getRole(c));
    }

    @Test @DisplayName("재발급 오류: 아직 유효 400 / 횟수 0·클레임 없음 401 REISSUE_LIMIT / 만료 24h 초과 401 REISSUE_EXPIRED")
    void reissueErrors() {
        assertEquals("400 TOKEN_NOT_EXPIRED", result(() -> jwt.parseForReissue(jwt.createLoginToken(15, 1))));
        assertEquals("401 REISSUE_LIMIT", result(() -> jwt.parseForReissue(tokenExpired(60, 0))));
        assertEquals("401 REISSUE_LIMIT", result(() -> jwt.parseForReissue(tokenExpired(60, null))));
        assertEquals("OK", result(() -> jwt.parseForReissue(tokenExpired(86000, 2))));
        assertEquals("401 REISSUE_EXPIRED", result(() -> jwt.parseForReissue(tokenExpired(86500, 2))));
        assertEquals("401 UNAUTHORIZED", result(() -> jwt.parseForReissue("garbage")));
    }

    @Test @DisplayName("로그인 → 재발급 2번 → 3번째는 REISSUE_LIMIT (토큰 3개 = 약 3시간)")
    void reissueChain() {
        // 만료된 토큰을 흉내 내기 위해 ttl이 음수인 JwtUtil로 발급
        JwtUtil expiredIssuer = new JwtUtil(SECRET, -60, 2, 86400);
        String t1 = expiredIssuer.createLoginToken(15, 1);
        String t2 = expiredIssuer.reissue(jwt.parseForReissue(t1), 1);
        String t3 = expiredIssuer.reissue(jwt.parseForReissue(t2), 1);
        assertEquals("401 REISSUE_LIMIT", result(() -> jwt.parseForReissue(t3)));
    }

    // ------------------------------------------------------------ 로그아웃 (POST /logout)

    @Test @DisplayName("로그아웃: 만료 토큰도 서명만 맞으면 통과, 위조는 401")
    void logout() {
        assertEquals("OK", result(() -> jwt.parseIgnoringExpiry(tokenExpired(999999, 0))));
        assertEquals("401 UNAUTHORIZED", result(() -> jwt.parseIgnoringExpiry("garbage")));
    }

    // ------------------------------------------------------------ reauth (verify-password → profile/password)

    @Test @DisplayName("reauth: 10분, 같은 회원·같은 purpose만 통과, 아니면 403 REAUTH_REQUIRED")
    void reauth() {
        String ra = jwt.createReauthToken(15, JwtUtil.PURPOSE_PROFILE_UPDATE);
        System.out.println("reauth payload = " + payloadJson(ra));
        assertEquals("OK", result(() -> jwt.verifyReauthToken(ra, 15, JwtUtil.PURPOSE_PROFILE_UPDATE)));
        assertEquals("403 REAUTH_REQUIRED", result(() -> jwt.verifyReauthToken(ra, 16, JwtUtil.PURPOSE_PROFILE_UPDATE)));
        assertEquals("403 REAUTH_REQUIRED", result(() -> jwt.verifyReauthToken(ra, 15, JwtUtil.PURPOSE_PASSWORD_CHANGE)));
        assertEquals("403 REAUTH_REQUIRED", result(() -> jwt.verifyReauthToken((String) null, 15, JwtUtil.PURPOSE_PROFILE_UPDATE)));
        String login = jwt.createLoginToken(15, 1);
        assertEquals("403 REAUTH_REQUIRED", result(() -> jwt.verifyReauthToken(login, 15, JwtUtil.PURPOSE_PROFILE_UPDATE)));
    }

    @Test @DisplayName("reauth 토큰을 로그인 토큰 자리에 넣으면 401 UNAUTHORIZED")
    void reauthIsNotLogin() {
        String ra = jwt.createReauthToken(15, JwtUtil.PURPOSE_PASSWORD_CHANGE);
        assertEquals("401 UNAUTHORIZED", result(() -> jwt.parse(ra)));
    }

    // ------------------------------------------------------------ 의도된 취약점 (모의해킹용) — 깨지면 취약점이 사라진 것

    @Test @DisplayName("[취약점] 처음 토큰(reissue_num 2)을 계속 다시 보내면 무제한 재발급")
    void vulnReissueReplay() {
        String first = tokenExpired(60, 2);
        for (int i = 0; i < 10; i++) {
            assertEquals(1, jwt.getReissueNum(jwt.parse(jwt.reissue(jwt.parseForReissue(first), 1))));
        }
    }

    @Test @DisplayName("[취약점] 로그아웃해도 토큰은 exp까지 유효 (서버 폐기 없음)")
    void vulnLogoutKeepsToken() {
        String t = jwt.createLoginToken(15, 1);
        jwt.parseIgnoringExpiry(t);              // /logout 처리
        assertEquals("OK", result(() -> jwt.parse(t)));
    }

    @Test @DisplayName("[취약점] reauth 토큰은 1회용이 아님 (10분 안 재사용 가능)")
    void vulnReauthReusable() {
        String ra = jwt.createReauthToken(15, JwtUtil.PURPOSE_PASSWORD_CHANGE);
        for (int i = 0; i < 3; i++) jwt.verifyReauthToken(ra, 15, JwtUtil.PURPOSE_PASSWORD_CHANGE);
    }
}
