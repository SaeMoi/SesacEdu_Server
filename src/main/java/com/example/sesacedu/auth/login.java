package com.example.sesacedu.auth;

import com.example.sesacedu.global.JwtUtil;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * POST /login (Notion API 명세서: 로그인).
 *
 * 처리 순서: 1. 입력 검증(400 INVALID_INPUT) → 2. mail로 회원 조회, 없거나 MD5 불일치면 401 LOGIN_FAILED
 * → 3. status = 'restricted'면 403 ACCOUNT_RESTRICTED → 4. JWT 발급, 발급한 토큰을 users.token에 저장, 200.
 * 토큰은 응답 헤더 Authorization: Bearer {JWT}에만 넣고 본문에는 넣지 않는다.
 *
 * 의도된 취약점 (모의해킹용): salt 없는 MD5, 로그인 잠금·횟수 제한 없음.
 * 감사 로그(audit_logs)는 보류(모의해킹 단계에서 구현)라 기록하지 않는다.
 */
@RestController
public class login {

    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private static final int EMAIL_MAX = 100;
    private static final int PW_MAX = 30;

    private final JdbcTemplate jdbc;
    private final JwtUtil jwtUtil;

    public login(JdbcTemplate jdbc, JwtUtil jwtUtil) {
        this.jdbc = jdbc;
        this.jwtUtil = jwtUtil;
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> doLogin(@RequestBody(required = false) LoginRequest req) {
        if (req == null) req = new LoginRequest();
        
        // 1. 입력 검증. email은 공백 제거 + 소문자 변환, pw는 형식 검증 없이 길이만
        String email = req.email == null ? "" : req.email.trim().toLowerCase(Locale.ROOT);
        String pw = req.pw == null ? "" : req.pw;
        List<Map<String, String>> errors = new ArrayList<>();
        if (email.isEmpty()) errors.add(error("email", "이메일을 입력해주세요."));
        else if (email.length() > EMAIL_MAX) errors.add(error("email", "이메일은 최대 100자입니다."));
        else if (!EMAIL.matcher(email).matches()) errors.add(error("email", "이메일 형식이 올바르지 않습니다."));
        if (pw.isEmpty()) errors.add(error("pw", "비밀번호를 입력해주세요."));
        else if (pw.length() > PW_MAX) errors.add(error("pw", "비밀번호는 최대 30자입니다."));
        if (!errors.isEmpty()) return invalidInput(errors);

        // 2. 회원 조회 + MD5 비교
        // [의도된 취약점 — OWASP A03:2021 Injection] mail 값을 바인드 변수가 아니라 문자열로 이어 붙인다.
        //   UNION 으로 auth=3(관리자) + 공격자가 아는 MD5 해시를 가진 가짜 행을 반환시키면 권한 상승 로그인이 된다.
        String memberSql = "SELECT user_id, mail, pw, uname, nickname, auth, status FROM users WHERE mail = '" + email + "'";
        List<Map<String, Object>> rows = jdbc.queryForList(memberSql);
        String md5 = md5Hex(pw);
        if (rows.isEmpty() || !md5.equalsIgnoreCase(String.valueOf(rows.get(0).get("PW")))) {
            return ResponseEntity.status(401).body(body("LOGIN_FAILED", "이메일 또는 비밀번호를 확인해주세요.", null));
        }
        Map<String, Object> user = rows.get(0);

        // 3. 비밀번호 확인 뒤 제한계정 검사
        if ("restricted".equals(user.get("STATUS"))) {
            return ResponseEntity.status(403).body(body("ACCOUNT_RESTRICTED", "이용이 제한된 계정입니다.", null));
        }

        // 4. JWT 발급 + 마지막 발급 토큰 저장
        long userId = ((Number) user.get("USER_ID")).longValue();
        int auth = ((Number) user.get("AUTH")).intValue();
        String token = jwtUtil.createLoginToken(userId, auth);
        jdbc.update("UPDATE users SET token = ? WHERE user_id = ?", token, userId);

        Map<String, Object> member = new LinkedHashMap<>();
        member.put("memberId", userId);
        member.put("name", user.get("UNAME"));
        member.put("nickname", user.get("NICKNAME"));
        member.put("role", JwtUtil.roleOf(auth));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("member", member);

        return ResponseEntity.ok()
                .header(HttpHeaders.AUTHORIZATION, jwtUtil.toHeaderValue(token))
                .header(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, HttpHeaders.AUTHORIZATION)
                .body(body("LOGIN_SUCCESS", "로그인되었습니다.", result));
    }

    /** 본문이 JSON이 아니거나 깨진 경우도 400 INVALID_INPUT */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> unreadable() {
        List<Map<String, String>> errors = new ArrayList<>();
        errors.add(error("body", "JSON 형식이 올바르지 않습니다."));
        return invalidInput(errors);
    }

    /** salt 없는 MD5 hex (소문자 32자). DB의 LOWER(RAWTOHEX(STANDARD_HASH(pw, 'MD5')))와 같은 값 */
    private static String md5Hex(String s) {
        try {
            byte[] d = MessageDigest.getInstance("MD5").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(32);
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ResponseEntity<Map<String, Object>> invalidInput(List<Map<String, String>> errors) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("errors", errors);
        return ResponseEntity.badRequest().body(body("INVALID_INPUT", "입력값을 확인해주세요.", result));
    }

    private static Map<String, String> error(String field, String reason) {
        Map<String, String> e = new LinkedHashMap<>();
        e.put("field", field);
        e.put("reason", reason);
        return e;
    }

    private static Map<String, Object> body(String code, String message, Object result) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("code", code);
        b.put("message", message);
        b.put("result", result);
        return b;
    }

    /** 요청 본문 { email, pw } */
    public static class LoginRequest {
        public String email;
        public String pw;
    }
}
