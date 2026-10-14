package com.example.sesacedu.mypage.profile;

import com.example.sesacedu.global.JwtUtil;
import com.example.sesacedu.global.JwtUtil.JwtAuthException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.DigestUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.example.sesacedu.global.ApiResponse.*;

/**
 * POST /mypage/verify-password (Notion API 명세서: 현재 비밀번호 확인 (본인 확인)).
 *
 * 처리 순서: 1. Authorization의 JWT 검증 (없음·위조 401 UNAUTHORIZED, 만료 401 TOKEN_EXPIRED)
 * → 2. 입력 검증 (pw 필수·최대 30자, purpose = PROFILE_UPDATE / PASSWORD_CHANGE, 아니면 400 INVALID_INPUT)
 * → 3. 토큰 sub로 users.pw 조회, md5Hex(pw)와 다르면 401 PASSWORD_MISMATCH
 * → 4. reauth JWT { sub, typ:"reauth", purpose, exp(+600초) } 발급, 200 PASSWORD_VERIFIED { reauthToken, expiresIn }.
 * 회원은 토큰 sub로만 정한다 (요청에 회원 ID 없음). reauthToken은 서버에 저장하지 않는다.
 * PUT /mypage/profile, PUT /mypage/password가 X-Reauth-Token으로 받아 JwtUtil.verifyReauthToken으로 검증한다.
 *
 * 의도된 취약점 (모의해킹용): salt 없는 MD5, 확인 횟수 제한·잠금 없음(탈취한 토큰으로 비밀번호 무차별 대입 가능),
 */
@RestController
public class verify_password {

    private static final int PW_MAX = 30;

    private final JdbcTemplate jdbc;
    private final JwtUtil jwtUtil;

    public verify_password(JdbcTemplate jdbc, JwtUtil jwtUtil) {
        this.jdbc = jdbc;
        this.jwtUtil = jwtUtil;
    }

    @PostMapping("/mypage/verify-password")
    public ResponseEntity<Map<String, Object>> verify(@RequestBody(required = false) VerifyRequest req,
                                                      HttpServletRequest request) {
        // 1. JWT 검증 (입력 검증보다 먼저: 토큰 없이 400/401 차이로 아무것도 알 수 없게)
        long userId = jwtUtil.getUid(request);
        if (req == null) req = new VerifyRequest();

        // 2. 입력 검증. pw는 로그인과 같게 형식 검증 없이 길이만, 공백 제거 안 함
        String pw = req.pw == null ? "" : req.pw;
        String purpose = req.purpose == null ? "" : req.purpose.trim();
        List<Map<String, String>> errors = new ArrayList<>();
        if (pw.isEmpty()) errors.add(error("pw", "비밀번호를 입력해주세요."));
        else if (pw.length() > PW_MAX) errors.add(error("pw", "비밀번호는 최대 30자입니다."));
        if (!JwtUtil.PURPOSE_PROFILE_UPDATE.equals(purpose) && !JwtUtil.PURPOSE_PASSWORD_CHANGE.equals(purpose)) {
            errors.add(error("purpose", "PROFILE_UPDATE 또는 PASSWORD_CHANGE만 사용할 수 있습니다."));
        }
        if (!errors.isEmpty()) return invalidInput(errors);

        // 3. 현재 해시 조회 + MD5 비교 (횟수 제한 없음 = 의도된 취약점)
        List<String> hashes = jdbc.queryForList("SELECT pw FROM users WHERE user_id = ?", String.class, userId);
        if (hashes.isEmpty()) {
            // 서명은 맞지만 회원이 없는 토큰 (탈퇴 등) → 인증 실패로 처리
            throw new JwtAuthException(401, "UNAUTHORIZED", "인증 정보가 올바르지 않습니다.");
        }
        String md5 = DigestUtils.md5DigestAsHex(pw.getBytes(StandardCharsets.UTF_8));
        if (!md5.equalsIgnoreCase(hashes.get(0))) {
            return ResponseEntity.status(401).body(body("PASSWORD_MISMATCH", "비밀번호가 올바르지 않습니다.", null));
        }

        // 4. reauth JWT 발급 (서버 저장 없음, 1회용 아님)
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("reauthToken", jwtUtil.createReauthToken(userId, purpose));
        result.put("expiresIn", jwtUtil.getReauthTtlSeconds());
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(body("PASSWORD_VERIFIED", "본인 확인이 완료되었습니다.", result));
    }

    /** 요청 본문 { pw, purpose } */
    public static class VerifyRequest {
        public String pw;
        public String purpose;
    }
}
