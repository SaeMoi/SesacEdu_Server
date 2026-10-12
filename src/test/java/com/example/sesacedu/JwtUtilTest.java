package com.example.sesacedu.global;

import com.example.sesacedu.global.JwtUtil.JwtAuthException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JwtUtil 명세 테스트 (Spring 컨텍스트 없음). 각 테스트 = 명세 한 줄.
 * 실행: gradlew.bat test --tests com.example.sesacedu.global.JwtUtilTest
 * 의도된 취약점도 "그대로 동작하는지" 검사한다 — 실수로 막히면 이 테스트가 깨진다.
 */
class JwtUtilTest {

    static final String SECRET = "0123456789abcdef0123456789abcdef";
    final JwtUtil jwt = new JwtUtil(SECRET, 3600, 2, 86400);

    /** 예외면 "status CODE", 아니면 "OK" */
    static String result(Runnable r) {
        try {
            r.run();
            return "OK";
        } catch (JwtAuthException e) {
            return e.getStatus() + " " + e.getCode();
        }
    }

    /** 같은 비밀키로 직접 만든 로그인 토큰. secAgo > 0 이면 그만큼 전에 만료됨 */
    static String loginToken(long secAgo, Integer reissueNum) {
        var b = Jwts.builder().subject("15").claim("name", "김태형").claim("role", "STUDENT")
                .expiration(new Date(System.currentTimeMillis() - secAgo * 1000));
        if (reissueNum != null) b.claim("reissue_num", reissueNum);
        return b.signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)), Jwts.SIG.HS256).compact();
    }

    @Nested
    @DisplayName("로그인 토큰 발급")
    class Issue {
        @Test
        @DisplayName("sub=user_id, name, role, reissue_num=2, 유효시간 60분")
        void payload() {
            Claims c = jwt.parse(jwt.createLoginToken(15, "김태형", 1));
            assertEquals("15", c.getSubject());
            assertEquals("김태형", jwt.getName(c));
            assertEquals("STUDENT", jwt.getRole(c));
            assertEquals(2, jwt.getReissueNum(c));
            long ttl = (c.getExpiration().getTime() - System.currentTimeMillis()) / 1000;
            assertTrue(ttl > 3590 && ttl <= 3600, "ttl=" + ttl);
        }

        @Test
        @DisplayName("헤더 alg=HS256")
        void hs256() {
            String header = new String(Base64.getUrlDecoder().decode(jwt.createLoginToken(1, "a", 0).split("\\.")[0]));
            assertTrue(header.contains("\"HS256\""), header);
        }

        @Test
        @DisplayName("users.auth 0~4 → GENERAL/STUDENT/INSTRUCTOR/ADMIN/INSTRUCTOR_PENDING, 범위 밖은 예외")
        void roles() {
            assertArrayEquals(new String[]{"GENERAL", "STUDENT", "INSTRUCTOR", "ADMIN", "INSTRUCTOR_PENDING"},
                    new String[]{JwtUtil.roleOf(0), JwtUtil.roleOf(1), JwtUtil.roleOf(2), JwtUtil.roleOf(3), JwtUtil.roleOf(4)});
            assertThrows(IllegalArgumentException.class, () -> JwtUtil.roleOf(5));
        }

        @Test
        @DisplayName("응답 헤더 값은 'Bearer {jwt}'")
        void headerValue() {
            assertEquals("Bearer abc", jwt.toHeaderValue("abc"));
        }

        @Test
        @DisplayName("32바이트 미만 비밀키는 기동 실패")
        void weakKey() {
            assertThrows(RuntimeException.class, () -> new JwtUtil("short", 3600, 2, 86400));
        }
    }

    @Nested
    @DisplayName("인증 (parse)")
    class Parse {
        @Test
        @DisplayName("변조 → 401 UNAUTHORIZED")
        void tampered() {
            assertEquals("401 UNAUTHORIZED", result(() -> jwt.parse(jwt.createLoginToken(15, "a", 1) + "x")));
        }

        @Test
        @DisplayName("토큰 없음 → 401 UNAUTHORIZED")
        void missing() {
            assertEquals("401 UNAUTHORIZED", result(() -> jwt.parse((String) null)));
            assertEquals("401 UNAUTHORIZED", result(() -> jwt.parse("")));
        }

        @Test
        @DisplayName("다른 키로 서명 → 401 UNAUTHORIZED")
        void otherKey() {
            String t = new JwtUtil("ffffffffffffffffffffffffffffffff", 3600, 2, 86400).createLoginToken(15, "a", 1);
            assertEquals("401 UNAUTHORIZED", result(() -> jwt.parse(t)));
        }

        @Test
        @DisplayName("만료 → 401 TOKEN_EXPIRED")
        void expired() {
            assertEquals("401 TOKEN_EXPIRED", result(() -> jwt.parse(loginToken(60, 2))));
        }

        @Test
        @DisplayName("alg=none (서명 없음) → 401 UNAUTHORIZED")
        void algNone() {
            String unsigned = Jwts.builder().subject("15").claim("role", "ADMIN")
                    .expiration(new Date(System.currentTimeMillis() + 60000)).compact();
            assertEquals("401 UNAUTHORIZED", result(() -> jwt.parse(unsigned)));
        }

        @Test
        @DisplayName("같은 키로 HS512 서명 → 401 UNAUTHORIZED (HS256만 허용)")
        void hs512() {
            String key64 = SECRET + SECRET;
            String t = Jwts.builder().subject("15").expiration(new Date(System.currentTimeMillis() + 60000))
                    .signWith(Keys.hmacShaKeyFor(key64.getBytes(StandardCharsets.UTF_8)), Jwts.SIG.HS512).compact();
            assertEquals("401 UNAUTHORIZED", result(() -> new JwtUtil(key64, 3600, 2, 86400).parse(t)));
        }

        @Test
        @DisplayName("reauth 토큰을 Authorization에 넣으면 → 401 UNAUTHORIZED")
        void reauthAsLogin() {
            assertEquals("401 UNAUTHORIZED", result(() -> jwt.parse(jwt.createReauthToken(15, JwtUtil.PURPOSE_PASSWORD_CHANGE))));
        }

        @Test
        @DisplayName("role이 맞지 않으면 403 FORBIDDEN, 맞으면 통과")
        void requireRole() {
            Claims c = jwt.parse(jwt.createLoginToken(15, "a", 1));
            assertEquals("403 FORBIDDEN", result(() -> jwt.requireRole(c, "ADMIN")));
            assertEquals("OK", result(() -> jwt.requireRole(c, "ADMIN", "STUDENT")));
        }

        @Test
        @DisplayName("Authorization 헤더: 'Bearer x'만 인정")
        void resolve() {
            assertEquals("abc", jwt.resolveToken("Bearer abc"));
            assertNull(jwt.resolveToken("Basic abc"));
            assertNull(jwt.resolveToken("Bearer "));
            assertNull(jwt.resolveToken((String) null));
        }
    }

    @Nested
    @DisplayName("로그아웃 (parseIgnoringExpiry)")
    class Logout {
        @Test
        @DisplayName("만료 토큰도 허용")
        void expiredOk() {
            assertEquals("OK", result(() -> jwt.parseIgnoringExpiry(loginToken(60, 2))));
        }

        @Test
        @DisplayName("변조 토큰은 401 UNAUTHORIZED")
        void tampered() {
            assertEquals("401 UNAUTHORIZED", result(() -> jwt.parseIgnoringExpiry(loginToken(60, 2) + "x")));
        }
    }

    @Nested
    @DisplayName("재발급 (parseForReissue + reissue)")
    class Reissue {
        @Test
        @DisplayName("아직 유효한 토큰 → 400 TOKEN_NOT_EXPIRED")
        void notExpired() {
            assertEquals("400 TOKEN_NOT_EXPIRED", result(() -> jwt.parseForReissue(jwt.createLoginToken(15, "a", 1))));
        }

        @Test
        @DisplayName("만료 토큰 → 새 토큰: sub 유지, reissue_num-1, name·role은 DB 값")
        void reissue() {
            Claims c = jwt.parse(jwt.reissue(jwt.parseForReissue(loginToken(60, 2)), "새이름", 2));
            assertEquals(15, jwt.getUid(c));
            assertEquals(1, jwt.getReissueNum(c));
            assertEquals("새이름", jwt.getName(c));
            assertEquals("INSTRUCTOR", jwt.getRole(c));
        }

        @Test
        @DisplayName("reissue_num 0 또는 클레임 없음 → 401 REISSUE_LIMIT")
        void limit() {
            assertEquals("401 REISSUE_LIMIT", result(() -> jwt.parseForReissue(loginToken(60, 0))));
            assertEquals("401 REISSUE_LIMIT", result(() -> jwt.parseForReissue(loginToken(60, null))));
        }

        @Test
        @DisplayName("만료 후 24시간 초과 → 401 REISSUE_EXPIRED")
        void grace() {
            assertEquals("401 REISSUE_EXPIRED", result(() -> jwt.parseForReissue(loginToken(86400 + 60, 2))));
            assertEquals("OK", result(() -> jwt.parseForReissue(loginToken(86400 - 60, 2))));
        }

        @Test
        @DisplayName("변조 → 401 UNAUTHORIZED")
        void tampered() {
            assertEquals("401 UNAUTHORIZED", result(() -> jwt.parseForReissue(loginToken(60, 2) + "x")));
        }

        @Test
        @DisplayName("[의도된 취약점] 처음 만료 토큰을 다시 보내면 횟수 제한 없이 재발급")
        void unlimitedReplay() {
            String first = loginToken(60, 2);
            for (int i = 0; i < 5; i++) {
                assertEquals(1, jwt.getReissueNum(jwt.parse(jwt.reissue(jwt.parseForReissue(first), "a", 1))));
            }
        }
    }

    @Nested
    @DisplayName("reauth 토큰 (X-Reauth-Token)")
    class Reauth {
        final String ra = jwt.createReauthToken(15, JwtUtil.PURPOSE_PROFILE_UPDATE);

        @Test
        @DisplayName("같은 사용자 + 같은 purpose → 통과, 유효시간 10분")
        void ok() {
            assertEquals("OK", result(() -> jwt.verifyReauthToken(ra, 15, JwtUtil.PURPOSE_PROFILE_UPDATE)));
            assertEquals(600, jwt.getReauthTtlSeconds());
        }

        @Test
        @DisplayName("다른 사용자 / 다른 purpose / 없음 / 로그인 토큰 → 403 REAUTH_REQUIRED")
        void rejected() {
            assertEquals("403 REAUTH_REQUIRED", result(() -> jwt.verifyReauthToken(ra, 16, JwtUtil.PURPOSE_PROFILE_UPDATE)));
            assertEquals("403 REAUTH_REQUIRED", result(() -> jwt.verifyReauthToken(ra, 15, JwtUtil.PURPOSE_PASSWORD_CHANGE)));
            assertEquals("403 REAUTH_REQUIRED", result(() -> jwt.verifyReauthToken((String) null, 15, JwtUtil.PURPOSE_PROFILE_UPDATE)));
            String login = jwt.createLoginToken(15, "a", 1);
            assertEquals("403 REAUTH_REQUIRED", result(() -> jwt.verifyReauthToken(login, 15, JwtUtil.PURPOSE_PROFILE_UPDATE)));
        }

        @Test
        @DisplayName("만료된 reauth 토큰 → 403 REAUTH_REQUIRED")
        void expired() {
            String old = Jwts.builder().subject("15").claim("typ", "reauth").claim("purpose", JwtUtil.PURPOSE_PROFILE_UPDATE)
                    .expiration(new Date(System.currentTimeMillis() - 1000))
                    .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)), Jwts.SIG.HS256).compact();
            assertEquals("403 REAUTH_REQUIRED", result(() -> jwt.verifyReauthToken(old, 15, JwtUtil.PURPOSE_PROFILE_UPDATE)));
        }

        @Test
        @DisplayName("[의도된 취약점] 1회용 아님: 10분 안에 여러 번 재사용 가능")
        void reusable() {
            for (int i = 0; i < 3; i++) {
                assertEquals("OK", result(() -> jwt.verifyReauthToken(ra, 15, JwtUtil.PURPOSE_PROFILE_UPDATE)));
            }
        }
    }
}
