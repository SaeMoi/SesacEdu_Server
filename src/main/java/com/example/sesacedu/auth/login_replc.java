package com.example.sesacedu.auth;

import com.example.sesacedu.global.JwtUtil;
import com.example.sesacedu.global.JwtUtil.JwtAuthException;
import io.jsonwebtoken.Claims;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * POST /login/replc (Notion API 명세서: JWT 재발급).
 *
 * 만료된 토큰을 Authorization: Bearer {만료된 토큰}으로 받아, reissue_num이 남아 있으면
 * reissue_num - 1인 새 토큰(60분)을 응답 헤더 Authorization으로 준다. 요청 본문 없음.
 *
 * 1. 서명 확인 → 401 UNAUTHORIZED            (JwtUtil.parseForReissue)
 * 2. 만료 확인 → 400 TOKEN_NOT_EXPIRED / 401 REISSUE_EXPIRED (만료 후 24시간 초과)
 * 3. 횟수 확인 → 401 REISSUE_LIMIT (reissue_num ≤ 0 또는 없음)
 * 4. 회원 확인 → 없으면 401 UNAUTHORIZED, restricted면 403 ACCOUNT_RESTRICTED
 * 5. 새 토큰: sub 유지, role은 DB에서 다시 읽은 auth, reissue_num - 1
 * 6. 감사 로그 TOKEN_REISSUE는 audit_logs 보류(모의해킹 단계에서 구현)라 기록하지 않는다.
 * 7. 200 REISSUE_SUCCESS, result.reissueNum
 */
@RestController
public class login_replc {

    private final JdbcTemplate jdbc;
    private final JwtUtil jwtUtil;

    public login_replc(JdbcTemplate jdbc, JwtUtil jwtUtil) {
        this.jdbc = jdbc;
        this.jwtUtil = jwtUtil;
    }

    @PostMapping("/login/replc")
    public ResponseEntity<Map<String, Object>> reissue(HttpServletRequest request) {
        // 1~3단계
        Claims claims = jwtUtil.parseForReissue(jwtUtil.requireToken(request));
        long userId = jwtUtil.getUid(claims);

        // 4단계: 회원 확인 + role·status 다시 읽기
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT user_id, mail, auth, status FROM users WHERE user_id = ?", userId);
        if (rows.isEmpty()) {
            throw new JwtAuthException(401, "UNAUTHORIZED", "인증 정보가 올바르지 않습니다.");
        }
        Map<String, Object> user = rows.get(0);
        if ("restricted".equals(user.get("STATUS"))) {
            return ResponseEntity.status(403).body(body("ACCOUNT_RESTRICTED", "이용이 제한된 계정입니다.", null));
        }

        // 5단계
        int auth = ((Number) user.get("AUTH")).intValue();
        String token = jwtUtil.reissue(claims, auth);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("reissueNum", jwtUtil.getReissueNum(claims) - 1);
        return ResponseEntity.ok()
                .header(HttpHeaders.AUTHORIZATION, jwtUtil.toHeaderValue(token))
                .header(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, HttpHeaders.AUTHORIZATION)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(body("REISSUE_SUCCESS", "로그인이 연장되었습니다.", result));
    }

    @ExceptionHandler(JwtAuthException.class)
    public ResponseEntity<Map<String, Object>> jwtError(JwtAuthException e) {
        return ResponseEntity.status(e.getStatus()).body(body(e.getCode(), e.getMessage(), null));
    }

    private static Map<String, Object> body(String code, String message, Object result) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("code", code);
        b.put("message", message);
        b.put("result", result);
        return b;
    }
}
