package com.example.sesacedu.mypage.profile;

import com.example.sesacedu.global.JwtUtil;
import com.example.sesacedu.global.JwtUtil.JwtAuthException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.DigestUtils;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static com.example.sesacedu.global.ApiResponse.*;

/**
 * PUT /mypage/password (Notion API 명세서: 비밀번호 변경).
 *
 * 처리 순서: 1. Authorization의 JWT 검증 (없음·위조 401 UNAUTHORIZED, 만료 401 TOKEN_EXPIRED)
 * → 2. X-Reauth-Token 검증 (purpose=PASSWORD_CHANGE, 아니면 403 REAUTH_REQUIRED)
 * → 3. 입력 검증 (newPw 8~30자 영문·숫자·특수문자 = 회원가입과 같음, newPwCheck 일치, 아니면 400 INVALID_INPUT)
 * → 4. users.pw와 md5Hex(newPw)가 같으면 400 SAME_AS_CURRENT_PASSWORD → 5. UPDATE users.pw → 6. 200 PASSWORD_CHANGED.
 * 회원은 토큰 sub로만 정한다. 비밀번호 값은 로그에 남기지 않는다.
 * 감사 로그(PASSWORD_CHANGE)는 audit_logs 보류 결정에 따라 쓰지 않는다. 변경 알림 메일(선택)도 보내지 않는다.
 *
 * 의도된 취약점 (모의해킹용): salt 없는 MD5, 변경 후에도 기존 로그인 토큰은 exp까지 유효 (서버 폐기 수단 없음),
 * reauthToken 1회용 아님 (10분 안에 여러 번 변경 가능).
 */
@RestController
public class password {

    private static final Pattern PW = Pattern.compile("^(?=.*[A-Za-z])(?=.*[0-9])(?=.*[^A-Za-z0-9\\s]).{8,30}$");

    private final JdbcTemplate jdbc;
    private final JwtUtil jwtUtil;

    public password(JdbcTemplate jdbc, JwtUtil jwtUtil) {
        this.jdbc = jdbc;
        this.jwtUtil = jwtUtil;
    }

    @PutMapping("/mypage/password")
    public ResponseEntity<Map<String, Object>> change(@RequestBody(required = false) PasswordRequest req,
                                                      HttpServletRequest request) {
        // 1~2. JWT + 본인 확인 토큰 (입력 검증보다 먼저)
        long userId = authenticate(request);
        if (req == null) req = new PasswordRequest();

        // 3. 입력 검증. 비밀번호는 로그인·회원가입과 같게 공백 제거 안 함
        String newPw = req.newPw == null ? "" : req.newPw;
        String newPwCheck = req.newPwCheck == null ? "" : req.newPwCheck;
        List<Map<String, String>> errors = new ArrayList<>();
        if (newPw.isEmpty()) errors.add(error("newPw", "새 비밀번호를 입력해주세요."));
        else if (!PW.matcher(newPw).matches()) errors.add(error("newPw", "비밀번호는 8~30자, 영문·숫자·특수문자를 포함해야 합니다."));
        if (newPwCheck.isEmpty()) errors.add(error("newPwCheck", "새 비밀번호 확인을 입력해주세요."));
        else if (!newPwCheck.equals(newPw)) errors.add(error("newPwCheck", "새 비밀번호가 일치하지 않습니다."));
        if (!errors.isEmpty()) return invalidInput(errors);

        // 4. 현재 해시와 비교
        List<String> hashes = jdbc.queryForList("SELECT pw FROM users WHERE user_id = ?", String.class, userId);
        if (hashes.isEmpty()) {
            // 서명은 맞지만 회원이 없는 토큰 (탈퇴 등) → 인증 실패 (verify-password와 같음)
            throw new JwtAuthException(401, "UNAUTHORIZED", "인증 정보가 올바르지 않습니다.");
        }
        String md5 = DigestUtils.md5DigestAsHex(newPw.getBytes(StandardCharsets.UTF_8));
        if (md5.equalsIgnoreCase(hashes.get(0))) {
            return ResponseEntity.badRequest()
                    .body(body("SAME_AS_CURRENT_PASSWORD", "현재 비밀번호와 다른 비밀번호를 입력해주세요.", null));
        }

        // 5. 변경 (salt 없는 MD5 = 의도된 취약점). 기존 토큰은 폐기하지 못한다
        jdbc.update("UPDATE users SET pw = ?, updated = LOCALTIMESTAMP WHERE user_id = ?", md5, userId);

        // 6. 프론트가 저장된 토큰을 지우고 /login으로 보낸다
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(body("PASSWORD_CHANGED", "비밀번호가 변경되었습니다. 다시 로그인해주세요.", null));
    }

    private long authenticate(HttpServletRequest request) {
        long userId = jwtUtil.getUid(request);
        jwtUtil.verifyReauthToken(request, userId, JwtUtil.PURPOSE_PASSWORD_CHANGE);
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

    /** 요청 본문 { newPw, newPwCheck } */
    public static class PasswordRequest {
        public String newPw;
        public String newPwCheck;
    }
}
