package com.example.sesacedu.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * POST /email-auth (Notion API 명세서: 이메일 중복 및 인증번호 요청).
 *
 * 처리 순서: 1. 입력 검증(400 INVALID_INPUT) → 2. users에 같은 mail 있으면 409 DUPLICATE_EMAIL
 * → 3. 발송 제한(같은 이메일 60초 / 이메일당 1시간 5회 / IP당 1시간 20회) 429 TOO_MANY_REQUESTS
 * → 4. 기존 entry 버리고 새 6자리 번호(SecureRandom) 저장 → 5. Gmail SMTP 발송, 실패하면 번호 버리고 503
 * → 6. 200 EMAIL_SENT { expiresIn: 300, resendAfter: 60 }.
 * 인증번호는 EmailAuthStore(서버 메모리)에만 두고 응답·로그에 넣지 않는다 (local 프로필에서만 콘솔 출력).
 * 로그인 전 API라 Authorization 불필요.
 * */
@RestController
public class email_auth {

    private static final Logger log = LoggerFactory.getLogger(email_auth.class);
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private static final int EMAIL_MAX = 100;

    private final JdbcTemplate jdbc;
    private final EmailAuthStore store;
    private final ObjectProvider<JavaMailSender> mailSender;   // spring.mail.host가 없으면 빈이 없음 → 503
    private final Environment env;
    private final String from;

    public email_auth(JdbcTemplate jdbc, EmailAuthStore store, ObjectProvider<JavaMailSender> mailSender,
                      Environment env, @Value("${app.mail.from:${spring.mail.username:}}") String from) {
        this.jdbc = jdbc;
        this.store = store;
        this.mailSender = mailSender;
        this.env = env;
        this.from = from;
    }

    @PostMapping("/email-auth")
    public ResponseEntity<Map<String, Object>> emailAuth(@RequestBody(required = false) EmailAuthRequest req,
                                                         HttpServletRequest request) {
        if (req == null) req = new EmailAuthRequest();

        // 1. 입력 검증. email은 공백 제거 + 소문자 변환
        String email = req.email == null ? "" : req.email.trim().toLowerCase(Locale.ROOT);
        List<Map<String, String>> errors = new ArrayList<>();
        if (email.isEmpty()) errors.add(error("email", "이메일을 입력해주세요."));
        else if (email.length() > EMAIL_MAX) errors.add(error("email", "이메일은 최대 100자입니다."));
        else if (!EMAIL.matcher(email).matches()) errors.add(error("email", "이메일 형식이 올바르지 않습니다."));
        if (!errors.isEmpty()) return invalidInput(errors);

        // 2. 이미 가입된 이메일 (가입 여부 노출 = 의도된 취약점)
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE mail = ?", Integer.class, email);
        if (count != null && count > 0) {
            return ResponseEntity.status(409).body(body("DUPLICATE_EMAIL", "이미 가입된 이메일입니다.", null));
        }

        // 3. 발송 제한
        String ip = request.getRemoteAddr();
        long retryAfter = store.retryAfterSeconds(email, ip);
        if (retryAfter > 0) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("retryAfter", retryAfter);
            return ResponseEntity.status(429)
                    .header(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfter))
                    .body(body("TOO_MANY_REQUESTS", "잠시 후 다시 시도해주세요.", result));
        }

        // 4~5. 새 번호 저장 후 발송. 실패하면 번호를 버리고 503
        String code = store.issue(email, ip);
        try {
            send(email, code);
        } catch (MailException | IllegalStateException e) {
            store.sendFailed(email, ip);
            log.warn("인증 메일 발송 실패: {}", e.toString());
            return ResponseEntity.status(503)
                    .body(body("EMAIL_SEND_FAILED", "인증 메일 발송에 실패했습니다. 잠시 후 다시 시도해주세요.", null));
        }
        if (env.acceptsProfiles(Profiles.of("local"))) {
            log.info("[local] {} 인증번호: {}", email, code);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("expiresIn", EmailAuthStore.CODE_TTL.getSeconds());
        result.put("resendAfter", EmailAuthStore.RESEND_AFTER.getSeconds());
        return ResponseEntity.ok(body("EMAIL_SENT", "인증번호가 발송되었습니다.", result));
    }

    private void send(String to, String code) {
        JavaMailSender sender = mailSender.getIfAvailable();
        if (sender == null) throw new IllegalStateException("JavaMailSender 없음 (spring.mail.host 미설정)");
        SimpleMailMessage msg = new SimpleMailMessage();
        if (from != null && !from.isEmpty()) msg.setFrom(from);
        msg.setTo(to);
        msg.setSubject("[온라인 강의] 회원가입 인증번호");
        msg.setText("인증번호: " + code + "\n5분 안에 회원가입 화면에 입력해주세요.\n본인이 요청하지 않았다면 이 메일을 무시하세요.");
        sender.send(msg);              // 실패하면 MailException
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
        log.error("POST /email-auth 처리 중 오류", e);
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

    /** 요청 본문 { email } */
    public static class EmailAuthRequest {
        public String email;
    }
}
