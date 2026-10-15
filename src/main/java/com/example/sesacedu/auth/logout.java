package com.example.sesacedu.auth;

import com.example.sesacedu.global.JwtUtil;
import com.example.sesacedu.global.JwtUtil.JwtAuthException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * POST /logout (Notion API 명세서: 로그아웃).
 *
 * 1. Authorization의 JWT 서명만 확인한다. exp는 보지 않는다 (만료 토큰도 로그아웃 허용).
 *    토큰 없음·서명 불일치·alg ≠ HS256 → 401 UNAUTHORIZED.
 * 2. 서버 상태 변경 없음: 토큰을 무효화하지 않는다 (의도된 취약점: 복사해 둔 토큰은 exp까지 동작).
 * 3. 감사 로그 LOGOUT은 audit_logs 보류(모의해킹 단계에서 구현)라 기록하지 않는다.
 * 4. 204, 본문 없음. 같은 토큰으로 다시 보내도 204 (멱등). 프론트가 보관하던 토큰을 지운다.
 */
@RestController
public class logout {

    private final JwtUtil jwtUtil;

    public logout(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> doLogout(HttpServletRequest request) {
        jwtUtil.parseIgnoringExpiry(jwtUtil.requireToken(request));
        return ResponseEntity.noContent().header(HttpHeaders.CACHE_CONTROL, "no-store").build();
    }

    /** JwtUtil이 던진 401 → { code, message, result: null } */
    @ExceptionHandler(JwtAuthException.class)
    public ResponseEntity<Map<String, Object>> jwtError(JwtAuthException e) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("code", e.getCode());
        b.put("message", e.getMessage());
        b.put("result", null);
        return ResponseEntity.status(e.getStatus()).body(b);
    }
}
