package com.example.sesacedu.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.DigestUtils;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * POST /signup (Notion API 명세서: 회원가입).
 *
 * 처리 순서: 1. 입력 검증(400 INVALID_INPUT, 틀린 필드를 result.errors에 모두)
 * → 2. EmailAuthStore에서 email의 verificationToken 확인 (일치, 인증 후 30분 이내, 미사용) 아니면 400 INVALID_VERIFICATION
 * → 3. mail 중복 409 DUPLICATE_EMAIL → 4. nickname 중복 409 DUPLICATE_NICKNAME
 * → 5. users INSERT (pw = MD5, auth = 0, status = 'active') 후 메모리 entry 제거(토큰 1회용) → 201 SIGNUP_SUCCESS { memberId }.
 * 가입만 하고 토큰은 발급하지 않는다 (프론트가 /login으로 이동). 로그인 전 API라 Authorization 불필요.
 *
 * auth·status는 요청에서 받지 않는다 (요청 클래스에 필드 없음). "auth": 3 을 보내도 무시 → Mass Assignment 없음.
 * nickname은 DB UNIQUE 없이 4단계 앱 검사만 한다. mail은 UNIQUE 위반(동시 가입)을 409 DUPLICATE_EMAIL로 바꾼다.
 * 감사 로그 SIGNUP은 audit_logs 보류(모의해킹 단계에서 구현)라 기록하지 않는다.
 *
 * 의도된 취약점 (모의해킹용): salt 없는 MD5, DUPLICATE_EMAIL로 계정 열거,
 * 인증번호 확인 횟수 제한 없음(/email-auth/verify)이라 번호를 맞히면 남의 이메일로 가입 가능.
 */
@RestController
public class signup {

    private static final Logger log = LoggerFactory.getLogger(signup.class);
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    // 8~30자, 영문 + 숫자 + 특수문자 각 1자 이상
    private static final Pattern PW = Pattern.compile("^(?=.*[A-Za-z])(?=.*[0-9])(?=.*[^A-Za-z0-9\\s]).{8,30}$");
    private static final DateTimeFormatter YMD = DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT);
    private static final int EMAIL_MAX = 100;
    private static final int NAME_MAX = 10;
    private static final int NICK_MIN = 2;
    private static final int NICK_MAX = 20;
    private static final int ADDRESS_MAX = 100;

    private final JdbcTemplate jdbc;
    private final EmailAuthStore store;

    public signup(JdbcTemplate jdbc, EmailAuthStore store) {
        this.jdbc = jdbc;
        this.store = store;
    }

    @PostMapping("/signup")
    public ResponseEntity<Map<String, Object>> signup(@RequestBody(required = false) SignupRequest req) {
        if (req == null) req = new SignupRequest();

        // 1. 입력 검증. email은 공백 제거 + 소문자 변환, 나머지는 공백 제거 (pw·pwcheck는 로그인과 같게 그대로)
        String email = req.email == null ? "" : req.email.trim().toLowerCase(Locale.ROOT);
        String token = trim(req.verificationToken);
        String pw = req.pw == null ? "" : req.pw;
        String pwcheck = req.pwcheck == null ? "" : req.pwcheck;
        String name = trim(req.name);
        String nickname = trim(req.nickname);
        String bDate = trim(req.b_date);
        String address = trim(req.address);

        List<Map<String, String>> errors = new ArrayList<>();
        if (email.isEmpty()) errors.add(error("email", "이메일을 입력해주세요."));
        else if (email.length() > EMAIL_MAX) errors.add(error("email", "이메일은 최대 100자입니다."));
        else if (!EMAIL.matcher(email).matches()) errors.add(error("email", "이메일 형식이 올바르지 않습니다."));
        if (token.isEmpty()) errors.add(error("verificationToken", "이메일 인증을 완료해주세요."));
        if (pw.isEmpty()) errors.add(error("pw", "비밀번호를 입력해주세요."));
        else if (!PW.matcher(pw).matches()) errors.add(error("pw", "비밀번호는 8~30자, 영문·숫자·특수문자를 포함해야 합니다."));
        if (pwcheck.isEmpty()) errors.add(error("pwcheck", "비밀번호 확인을 입력해주세요."));
        else if (!pwcheck.equals(pw)) errors.add(error("pwcheck", "비밀번호가 일치하지 않습니다."));
        if (name.isEmpty()) errors.add(error("name", "이름을 입력해주세요."));
        else if (name.length() > NAME_MAX) errors.add(error("name", "이름은 최대 10자입니다."));
        if (nickname.isEmpty()) errors.add(error("nickname", "닉네임을 입력해주세요."));
        else if (nickname.length() < NICK_MIN || nickname.length() > NICK_MAX) errors.add(error("nickname", "닉네임은 2~20자입니다."));
        if (bDate.isEmpty()) errors.add(error("b_date", "생년월일을 입력해주세요."));
        else {
            try {
                if (!LocalDate.parse(bDate, YMD).isBefore(LocalDate.now())) errors.add(error("b_date", "생년월일은 오늘 이전 날짜여야 합니다."));
            } catch (DateTimeParseException e) {
                errors.add(error("b_date", "YYYY-MM-DD 형식으로 입력해주세요."));     // 2026-02-30 같은 날짜도 여기서 거름
            }
        }
        if (address.isEmpty()) errors.add(error("address", "주소를 입력해주세요."));
        else if (address.length() > ADDRESS_MAX) errors.add(error("address", "주소는 최대 100자입니다."));
        if (!errors.isEmpty()) return invalidInput(errors);

        // 2. 이메일 인증 확인 (서버 메모리, SQL 아님)
        if (!store.isVerified(email, token)) {
            return ResponseEntity.badRequest().body(body("INVALID_VERIFICATION",
                    "이메일 인증이 만료되었거나 올바르지 않습니다. 다시 인증해주세요.", null));
        }

        // 3. 이메일 중복 (가입 여부 노출 = 의도된 취약점)
        Integer mailCount = jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE mail = ?", Integer.class, email);
        if (mailCount != null && mailCount > 0) return duplicateEmail();

        // 4. 닉네임 중복 (DB UNIQUE 없음, 앱 검사만)
        Integer nickCount = jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE nickname = ?", Integer.class, nickname);
        if (nickCount != null && nickCount > 0) {
            return ResponseEntity.status(409).body(body("DUPLICATE_NICKNAME", "이미 사용 중인 닉네임입니다.", null));
        }

        // 5. 가입 (바인드 변수만 사용. 문자열 연결로 바꾸면 SQLi 지점이 됨)
        String md5 = DigestUtils.md5DigestAsHex(pw.getBytes(StandardCharsets.UTF_8));
        try {
            jdbc.update("INSERT INTO users (mail, pw, uname, nickname, b_date, address, auth, status) "
                            + "VALUES (?, ?, ?, ?, TO_DATE(?, 'YYYY-MM-DD'), ?, 0, 'active')",
                    email, md5, name, nickname, bDate, address);
        } catch (DuplicateKeyException e) {
            return duplicateEmail();                     // 3단계와 INSERT 사이에 같은 이메일이 먼저 가입한 경우
        }
        Long memberId = jdbc.queryForObject("SELECT user_id FROM users WHERE mail = ?", Long.class, email);
        store.consume(email);                            // 토큰 1회용

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("memberId", memberId);
        return ResponseEntity.status(201).body(body("SIGNUP_SUCCESS", "회원가입이 완료되었습니다.", result));
    }

    /** 본문이 JSON이 아니거나 깨진 경우도 400 INVALID_INPUT */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> unreadable() {
        List<Map<String, String>> errors = new ArrayList<>();
        errors.add(error("body", "JSON 형식이 올바르지 않습니다."));
        return invalidInput(errors);
    }

    /** 그 밖의 오류는 원인을 응답에 넣지 않고 로그에만 (비밀번호·토큰은 로그에 남기지 않음) */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> internal(Exception e) {
        log.error("POST /signup 처리 중 오류: {}", e.getClass().getName());
        return ResponseEntity.status(500).body(body("INTERNAL_ERROR", "서버 오류가 발생했습니다.", null));
    }

    private static ResponseEntity<Map<String, Object>> duplicateEmail() {
        return ResponseEntity.status(409).body(body("DUPLICATE_EMAIL", "이미 사용 중인 이메일입니다.", null));
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
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

    /** 요청 본문. auth·status 필드는 일부러 없다 (Mass Assignment 방지) */
    public static class SignupRequest {
        public String email;
        public String verificationToken;
        public String pw;
        public String pwcheck;
        public String name;
        public String nickname;
        public String b_date;
        public String address;
    }
}
