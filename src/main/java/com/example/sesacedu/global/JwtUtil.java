package com.example.sesacedu.global;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import javax.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

/**
 * JWT 발급·검증·디코딩 공통 헬퍼 (Notion API 명세서: 로그인 / JWT 재발급 / 로그아웃 / 현재 비밀번호 확인).
 *
 * - 로그인 토큰 : HS256, payload { sub(users.user_id 문자열), role, reissue_num, exp }, 60분
 * - reauth 토큰 : HS256, payload { sub, typ:"reauth", purpose, exp }, 10분, 헤더 X-Reauth-Token
 * - 서버 저장 없음 (Redis·토큰 테이블 없음). 이 클래스는 서명 키와 설정값만 가지며 회원별 상태를 두지 않는다.
 *
 * 실패는 {@link JwtAuthException}(HTTP 상태 + code + message)로 던진다. 전역 예외 처리기에서
 * { code, message, result:null } 로 변환하면 된다.
 **/
@Component
public class JwtUtil {

    public static final String AUTH_HEADER = "Authorization";
    public static final String REAUTH_HEADER = "X-Reauth-Token";
    public static final String BEARER = "Bearer ";

    public static final String PURPOSE_PROFILE_UPDATE = "PROFILE_UPDATE";
    public static final String PURPOSE_PASSWORD_CHANGE = "PASSWORD_CHANGE";

    /** users.auth(0~4) 순서대로의 role 코드 */
    private static final String[] ROLES = {"GENERAL", "STUDENT", "INSTRUCTOR", "ADMIN", "INSTRUCTOR_PENDING"};

    private static final String CLAIM_ROLE = "role";
    private static final String CLAIM_REISSUE = "reissue_num";
    private static final String CLAIM_TYP = "typ";
    private static final String CLAIM_PURPOSE = "purpose";
    private static final String TYP_REAUTH = "reauth";
    private static final long REAUTH_TTL_SECONDS = 600;

    private final SecretKey key;
    private final long ttlSeconds;
    private final int reissueMax;
    private final long reissueGraceSeconds;

    public JwtUtil(@Value("${JWT_SECRET}") String secret,   // 32바이트 이상이어야 함 (짧으면 기동 시 WeakKeyException)
                   @Value("${app.security.jwt.ttl-seconds:3600}") long ttlSeconds,
                   @Value("${app.security.jwt.reissue-max:2}") int reissueMax,
                   @Value("${app.security.jwt.reissue-grace-seconds:86400}") long reissueGraceSeconds) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.ttlSeconds = ttlSeconds;
        this.reissueMax = reissueMax;
        this.reissueGraceSeconds = reissueGraceSeconds;
    }

    // ================================================================ 발급

    public String createLoginToken(long userId, int auth) {
        return createToken(userId, roleOf(auth), reissueMax);
    }


    public String createToken(long userId, String role, int reissueNum) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim(CLAIM_ROLE, role)
                .claim(CLAIM_REISSUE, reissueNum)
                .expiration(Date.from(now.plusSeconds(ttlSeconds)))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    public String reissue(Claims expired, int auth) {
        return createToken(getUid(expired), roleOf(auth), getReissueNum(expired) - 1);
    }

    /** POST /mypage/verify-password: reauth JWT (10분). purpose = PROFILE_UPDATE / PASSWORD_CHANGE */
    public String createReauthToken(long userId, String purpose) {
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim(CLAIM_TYP, TYP_REAUTH)
                .claim(CLAIM_PURPOSE, purpose)
                .expiration(Date.from(Instant.now().plusSeconds(REAUTH_TTL_SECONDS)))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    public long getReauthTtlSeconds() { return REAUTH_TTL_SECONDS; }

    public String toHeaderValue(String token) { return BEARER + token; }

    public static String roleOf(int auth) {
        if (auth < 0 || auth >= ROLES.length) throw new IllegalArgumentException("unknown users.auth: " + auth);
        return ROLES[auth];
    }

    // ================================================================ 검증 (JwtFilter 등)

    public Claims parse(String token) {
        try {
            return checkLoginToken(verify(token).getPayload());
        } catch (ExpiredJwtException e) {
            checkAlg(e.getHeader().getAlgorithm());
            throw new JwtAuthException(401, "TOKEN_EXPIRED", "로그인이 만료되었습니다.");
        }
    }

    /** 요청의 Authorization 헤더로 {@link #parse(String)} */
    public Claims parse(HttpServletRequest request) {
        return parse(requireToken(request));
    }

    public Claims parseIgnoringExpiry(String token) {
        try {
            return checkLoginToken(verify(token).getPayload());
        } catch (ExpiredJwtException e) {
            checkAlg(e.getHeader().getAlgorithm());
            return checkLoginToken(e.getClaims()); // 서명은 exp 검사 전에 이미 검증됨
        }
    }


    public Claims parseForReissue(String token) {
        Claims claims;
        try {
            verify(token);
            throw new JwtAuthException(400, "TOKEN_NOT_EXPIRED", "아직 만료되지 않은 토큰입니다.");
        } catch (ExpiredJwtException e) {
            checkAlg(e.getHeader().getAlgorithm());
            claims = checkLoginToken(e.getClaims());
        }
        if (Instant.now().isAfter(claims.getExpiration().toInstant().plusSeconds(reissueGraceSeconds))) {
            throw new JwtAuthException(401, "REISSUE_EXPIRED", "로그인이 만료되었습니다. 다시 로그인해주세요.");
        }
        if (getReissueNum(claims) <= 0) {
            throw new JwtAuthException(401, "REISSUE_LIMIT", "로그인 시간이 끝났습니다. 다시 로그인해주세요.");
        }
        return claims;
    }


    public void verifyReauthToken(String reauthToken, long userId, String purpose) {
        try {
            Claims c = verify(reauthToken).getPayload();
            if (TYP_REAUTH.equals(c.get(CLAIM_TYP, String.class))
                    && String.valueOf(userId).equals(c.getSubject())
                    && purpose.equals(c.get(CLAIM_PURPOSE, String.class))) {
                return;
            }
        } catch (JwtException | JwtAuthException e) {
            // 아래에서 403
        }
        throw new JwtAuthException(403, "REAUTH_REQUIRED", "본인 확인이 필요합니다.");
    }

    public void verifyReauthToken(HttpServletRequest request, long userId, String purpose) {
        verifyReauthToken(request.getHeader(REAUTH_HEADER), userId, purpose);
    }

    public void requireRole(Claims claims, String... roles) {
        String role = getRole(claims);
        for (String r : roles) if (r.equals(role)) return;
        throw new JwtAuthException(403, "FORBIDDEN", "권한이 없습니다.");
    }

    // ================================================================ 헤더

    public String resolveToken(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith(BEARER)) return null;
        String token = authorizationHeader.substring(BEARER.length()).trim();
        return token.isEmpty() ? null : token;
    }

    public String resolveToken(HttpServletRequest request) {
        return resolveToken(request.getHeader(AUTH_HEADER));
    }

    public String requireToken(HttpServletRequest request) {
        String token = resolveToken(request);
        if (token == null) throw unauthorized();
        return token;
    }

    // ================================================================ 클레임 꺼내기

    public long getUid(Claims c) {
        try {
            return Long.parseLong(c.getSubject());
        } catch (NumberFormatException | NullPointerException e) {
            throw unauthorized();
        }
    }

    public String getRole(Claims c) { return c.get(CLAIM_ROLE, String.class); }

    public int getReissueNum(Claims c) {
        Number n = c.get(CLAIM_REISSUE, Number.class);
        return n == null ? 0 : n.intValue();
    }

    public long getUid(String token)          { return getUid(parse(token)); }
    public String getRole(String token)       { return getRole(parse(token)); }
    public int getReissueNum(String token)    { return getReissueNum(parse(token)); }

    public long getUid(HttpServletRequest r)       { return getUid(parse(r)); }
    public String getRole(HttpServletRequest r)    { return getRole(parse(r)); }
    public int getReissueNum(HttpServletRequest r) { return getReissueNum(parse(r)); }

    // ================================================================ 내부

    private Jws<Claims> verify(String token) {
        if (token == null || token.isBlank()) throw unauthorized();
        Jws<Claims> jws;
        try {
            jws = Jwts.parser().verifyWith(key).build().parseSignedClaims(token);
        } catch (ExpiredJwtException e) {
            throw e;
        } catch (JwtException | IllegalArgumentException e) {
            throw unauthorized();
        }
        checkAlg(jws.getHeader().getAlgorithm());
        return jws;
    }

    /** 같은 키로 HS384/HS512 서명한 토큰도 jjwt는 받아 주므로 명세대로 HS256만 허용 */
    private void checkAlg(String alg) {
        if (!"HS256".equals(alg)) throw unauthorized();
    }

    /** reauth 토큰을 Authorization에 넣어 로그인 토큰처럼 쓰는 것을 막는다 */
    private Claims checkLoginToken(Claims c) {
        if (c.get(CLAIM_TYP) != null || c.getSubject() == null) throw unauthorized();
        return c;
    }

    private static JwtAuthException unauthorized() {
        return new JwtAuthException(401, "UNAUTHORIZED", "인증 정보가 올바르지 않습니다.");
    }

    /** JWT 관련 실패. status = HTTP 상태, code·message = 응답 본문 { code, message, result:null } */
    public static class JwtAuthException extends RuntimeException {
        private final int status;
        private final String code;

        public JwtAuthException(int status, String code, String message) {
            super(message);
            this.status = status;
            this.code = code;
        }

        public int getStatus() { return status; }
        public String getCode() { return code; }
    }
}