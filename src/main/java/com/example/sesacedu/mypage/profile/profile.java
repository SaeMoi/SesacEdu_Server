package com.example.sesacedu.mypage.profile;

import com.example.sesacedu.global.JwtUtil;
import com.example.sesacedu.global.JwtUtil.JwtAuthException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import static com.example.sesacedu.global.ApiResponse.*;

/**
 * PUT /mypage/profile (Notion API 명세서: 프로필 수정).
 *
 * 처리 순서: 1. Authorization의 JWT 검증 (없음·위조 401 UNAUTHORIZED, 만료 401 TOKEN_EXPIRED)
 * → 2. X-Reauth-Token 검증 (서명·만료, typ=reauth, sub 일치, purpose=PROFILE_UPDATE, 아니면 403 REAUTH_REQUIRED)
 * → 3. 입력 검증 (400 INVALID_INPUT, result.errors) → 4. 닉네임·이메일 중복 (본인 제외, 409 DUPLICATE_NICKNAME / DUPLICATE_EMAIL)
 * → 5. UPDATE users → 6. 200 PROFILE_UPDATED (result는 GET /mypage와 같은 형식).
 * 회원은 토큰 sub로만 정한다. 본문 DTO에는 수정 가능한 5개 필드만 두어 memberId·role·auth·status 등은 무시된다 (Mass Assignment 방지).
 * 감사 로그(PROFILE_UPDATE)는 audit_logs 보류 결정에 따라 쓰지 않는다.
 *
 * 의도된 취약점 (모의해킹용): 이메일을 바꿔도 새 주소 재인증(/email-auth)을 하지 않는다,
 * DUPLICATE_EMAIL·DUPLICATE_NICKNAME로 사용 여부 확인 가능 (계정 열거).
 */
@RestController
public class profile {

    private static final int NAME_MAX = 10;
    private static final int NICK_MIN = 2;
    private static final int NICK_MAX = 20;
    private static final int EMAIL_MAX = 100;
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private static final Pattern PHONE = Pattern.compile("^[0-9]{10,11}$");
    private static final DateTimeFormatter YMD = DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT);

    private final JdbcTemplate jdbc;
    private final JwtUtil jwtUtil;
    private final ProfileImageStore images;

    public profile(JdbcTemplate jdbc, JwtUtil jwtUtil, ProfileImageStore images) {
        this.jdbc = jdbc;
        this.jwtUtil = jwtUtil;
        this.images = images;
    }

    @PutMapping("/mypage/profile")
    public ResponseEntity<Map<String, Object>> update(@RequestBody(required = false) ProfileRequest req,
                                                      HttpServletRequest request) {
        // 1~2. JWT + 본인 확인 토큰 (입력 검증보다 먼저)
        long userId = authenticate(request);
        if (req == null) req = new ProfileRequest();

        // 3. 입력 검증. 앞뒤 공백 제거, 이메일은 소문자. HTML은 그대로 저장 (출력 시 이스케이프는 프론트)
        String name = trim(req.name);
        String nickname = trim(req.nickname);
        String email = trim(req.email).toLowerCase(Locale.ROOT);
        String phone = trim(req.phone);
        String birthDate = trim(req.birthDate);
        List<Map<String, String>> errors = new ArrayList<>();
        if (name.isEmpty()) errors.add(error("name", "이름을 입력해주세요."));
        else if (name.length() > NAME_MAX) errors.add(error("name", "이름은 최대 10자입니다."));
        if (nickname.isEmpty()) errors.add(error("nickname", "닉네임을 입력해주세요."));
        else if (nickname.length() < NICK_MIN || nickname.length() > NICK_MAX) errors.add(error("nickname", "2~20자로 입력해주세요."));
        if (email.isEmpty()) errors.add(error("email", "이메일을 입력해주세요."));
        else if (email.length() > EMAIL_MAX) errors.add(error("email", "이메일은 최대 100자입니다."));
        else if (!EMAIL.matcher(email).matches()) errors.add(error("email", "이메일 형식이 올바르지 않습니다."));
        if (phone.isEmpty()) errors.add(error("phone", "연락처를 입력해주세요."));
        else if (!PHONE.matcher(phone).matches()) errors.add(error("phone", "하이픈 없이 숫자 10~11자리로 입력해주세요."));
        LocalDate bDate = null;
        if (birthDate.isEmpty()) errors.add(error("birthDate", "생년월일을 입력해주세요."));
        else {
            try {
                bDate = LocalDate.parse(birthDate, YMD);
                if (bDate.isAfter(LocalDate.now())) errors.add(error("birthDate", "미래 날짜는 입력할 수 없습니다."));
            } catch (DateTimeParseException e) {
                errors.add(error("birthDate", "YYYY-MM-DD 형식으로 입력해주세요."));     // 2026-02-30 같은 날짜도 여기서 거름
            }
        }
        if (!errors.isEmpty()) return invalidInput(errors);

        // 서명은 맞지만 회원이 없는 토큰 (탈퇴 등) → 인증 실패 (verify-password와 같음)
        Integer exists = jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE user_id = ?", Integer.class, userId);
        if (exists == null || exists == 0) throw new JwtAuthException(401, "UNAUTHORIZED", "인증 정보가 올바르지 않습니다.");

        // 4. 중복 (본인 현재 값은 제외). 닉네임은 UNIQUE 제약이 없어 앱에서만 검사
        Integer nickCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE nickname = ? AND user_id <> ?", Integer.class, nickname, userId);
        if (nickCount != null && nickCount > 0) return duplicateNickname();
        Integer mailCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE mail = ? AND user_id <> ?", Integer.class, email, userId);
        if (mailCount != null && mailCount > 0) return duplicateEmail();

        // 5. 수정 (이메일 재인증 없음 = 의도된 취약점)
        try {
            jdbc.update("UPDATE users SET uname = ?, nickname = ?, mail = ?, phone = ?, b_date = ?, updated = LOCALTIMESTAMP WHERE user_id = ?",
                    name, nickname, email, phone, java.sql.Date.valueOf(bDate), userId);
        } catch (DuplicateKeyException e) {
            return duplicateEmail();                     // 4단계와 UPDATE 사이에 다른 회원이 같은 이메일을 쓴 경우 (mail UNIQUE)
        }

        // 6. GET /mypage와 같은 조회로 응답
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(body("PROFILE_UPDATED", "프로필이 수정되었습니다.", mypage.loadProfile(jdbc, images, userId)));
    }

    private long authenticate(HttpServletRequest request) {
        long userId = jwtUtil.getUid(request);
        jwtUtil.verifyReauthToken(request, userId, JwtUtil.PURPOSE_PROFILE_UPDATE);
        return userId;
    }

    /** 본문이 JSON이 아니거나 깨진 경우도 400 INVALID_INPUT. 토큰·본인 확인을 먼저 본다 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> unreadable(HttpServletRequest request) {
        try {
            authenticate(request);
        } catch (JwtAuthException e) {
            return jwtError(e);
        }
        List<Map<String, String>> errors = new ArrayList<>();
        errors.add(error("body", "JSON 형식이 올바르지 않습니다."));
        return invalidInput(errors);
    }

    private static ResponseEntity<Map<String, Object>> duplicateNickname() {
        return ResponseEntity.status(409).body(body("DUPLICATE_NICKNAME", "이미 사용 중인 닉네임입니다.", null));
    }

    private static ResponseEntity<Map<String, Object>> duplicateEmail() {
        return ResponseEntity.status(409).body(body("DUPLICATE_EMAIL", "이미 사용 중인 이메일입니다.", null));
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    /** 요청 본문 { name, nickname, email, phone, birthDate }. 그 외 필드는 받지 않는다 */
    public static class ProfileRequest {
        public String name;
        public String nickname;
        public String email;
        public String phone;
        public String birthDate;
    }
}
