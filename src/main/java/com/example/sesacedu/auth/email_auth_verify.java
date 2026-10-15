package com.example.sesacedu.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * POST /email-auth/verify (Notion API 명세서: 이메일 인증번호 확인).
 *
 * 처리 순서: 1. 입력 검증(400 INVALID_INPUT) → 2. EmailAuthStore에서 이메일로 entry 조회,
 * 없거나 만료(5분)·재발송으로 무효면 400 CODE_EXPIRED → 3. code가 다르면 tries++ 후 400 INVALID_CODE (막지 않음)
 * → 4. 맞으면 verificationToken(랜덤 32자)·verifiedAt을 같은 entry에 저장하고 200 EMAIL_VERIFIED.
 * 토큰은 30분 유효, /signup 성공 시 entry가 지워져 1회용. 로그인 전 API라 Authorization 불필요.
**/
@RestController
public class email_auth_verify {

    private static final Logger log = LoggerFactory.getLogger(email_auth_verify.class);
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private static final Pattern CODE = Pattern.compile("^[0-9]{6}$");
    private static final int EMAIL_MAX = 100;

    private final EmailAuthStore store;

    public email_auth_verify(EmailAuthStore store) {
        this.store = store;
    }

    @PostMapping("/email-auth/verify")
    public ResponseEntity<Map<String, Object>> verify(@RequestBody(required = false) VerifyRequest req) {
        if (req == null) req = new VerifyRequest();

        // 1. 입력 검증. email은 공백 제거 + 소문자 변환, code는 문자열 숫자 6자리 (앞자리 0 유지)
        String email = req.email == null ? "" : req.email.trim().toLowerCase(Locale.ROOT);
        String code = req.code == null ? "" : req.code.trim();
        List<Map<String, String>> errors = new ArrayList<>();
        if (email.isEmpty()) errors.add(error("email", "이메일을 입력해주세요."));
        else if (email.length() > EMAIL_MAX) errors.add(error("email", "이메일은 최대 100자입니다."));
        else if (!EMAIL.matcher(email).matches()) errors.add(error("email", "이메일 형식이 올바르지 않습니다."));
        if (code.isEmpty()) errors.add(error("code", "인증번호를 입력해주세요."));
        else if (!CODE.matcher(code).matches()) errors.add(error("code", "인증번호는 숫자 6자리입니다."));
        if (!errors.isEmpty()) return invalidInput(errors);

        // 2~4. 확인 (틀린 횟수는 tries에 쌓기만 하고 막지 않음 = 의도된 취약점)
        switch (store.verify(email, code)) {
            case CODE_EXPIRED:
                return ResponseEntity.badRequest()
                        .body(body("CODE_EXPIRED", "인증번호가 만료되었습니다. 다시 요청해주세요.", null));
            case INVALID_CODE:
                return ResponseEntity.badRequest()
                        .body(body("INVALID_CODE", "인증번호가 올바르지 않습니다.", null));
            default:
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("verificationToken", store.tokenOf(email));
                result.put("expiresIn", EmailAuthStore.TOKEN_TTL.getSeconds());
                return ResponseEntity.ok(body("EMAIL_VERIFIED", "이메일 인증이 완료되었습니다.", result));
        }
    }

    /** 본문이 JSON이 아니거나 깨진 경우도 400 INVALID_INPUT */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> unreadable() {
        List<Map<String, String>> errors = new ArrayList<>();
        errors.add(error("body", "JSON 형식이 올바르지 않습니다."));
        return invalidInput(errors);
    }

    /** 그 밖의 오류는 원인을 응답에 넣지 않고 로그에만 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> internal(Exception e) {
        log.error("POST /email-auth/verify 처리 중 오류", e);
        return ResponseEntity.status(500).body(body("INTERNAL_ERROR", "서버 오류가 발생했습니다.", null));
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

    /** 요청 본문 { email, code } */
    public static class VerifyRequest {
        public String email;
        public String code;
    }
}
