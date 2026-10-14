package com.example.sesacedu.mypage.profile;

import com.example.sesacedu.global.JwtUtil;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.example.sesacedu.global.ApiResponse.*;

/**
 * GET /mypage (Notion API 명세서: 마이 페이지).
 *
 * 처리 순서: 1. Authorization의 JWT 검증 (없음·위조 401 UNAUTHORIZED, 만료 401 TOKEN_EXPIRED)
 * → 2. 토큰 sub(user_id)로 users 조회, 없으면 404 MEMBER_NOT_FOUND → 3. 200 PROFILE_FOUND.
 * 조회 대상은 토큰 sub로만 정한다 (경로·쿼리에 회원 ID를 받지 않음 = IDOR 방지).
 * 비밀번호 해시·주소 등 화면에 없는 값은 내려주지 않는다. 개인정보라 Cache-Control: no-store.
 * profileImageUrl은 사진 파일이 있으면 ?v={수정 시각}을 붙인다 (프로필 사진 변경 「파일명 고정 주의」).
 */
@RestController
public class mypage {

    private final JdbcTemplate jdbc;
    private final JwtUtil jwtUtil;
    private final ProfileImageStore images;

    public mypage(JdbcTemplate jdbc, JwtUtil jwtUtil, ProfileImageStore images) {
        this.jdbc = jdbc;
        this.jwtUtil = jwtUtil;
        this.images = images;
    }

    @GetMapping("/mypage")
    public ResponseEntity<Map<String, Object>> getMypage(HttpServletRequest request) {
        // 1. JWT 검증
        long userId = jwtUtil.getUid(request);

        // 2. 회원 조회
        Map<String, Object> result = loadProfile(jdbc, images, userId);
        if (result == null) {
            return ResponseEntity.status(404).header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .body(body("MEMBER_NOT_FOUND", "회원 정보를 찾을 수 없습니다.", null));
        }

        // 3. 응답
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(body("PROFILE_FOUND", "프로필을 조회했습니다.", result));
    }

    /**
     * 회원 1명의 프로필 result (명세 「응답 필드」 순서). 없으면 null.
     * PUT /mypage/profile 200 응답도 같은 형식이라 profile 컨트롤러가 같이 쓴다.
     */
    static Map<String, Object> loadProfile(JdbcTemplate jdbc, ProfileImageStore images, long userId) {
        // 바인드 변수만 사용
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT user_id, uname, mail, nickname, phone, TO_CHAR(b_date, 'YYYY-MM-DD') AS birth_date, "
                        + "picture, auth, created FROM users WHERE user_id = ?", userId);
        if (rows.isEmpty()) return null;
        Map<String, Object> user = rows.get(0);

        Object created = user.get("CREATED");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("memberId", ((Number) user.get("USER_ID")).longValue());
        result.put("name", user.get("UNAME"));
        result.put("email", user.get("MAIL"));
        result.put("nickname", user.get("NICKNAME"));
        result.put("phone", user.get("PHONE"));
        result.put("birthDate", user.get("BIRTH_DATE"));
        result.put("profileImageUrl", images.urlOf((String) user.get("PICTURE"))); // 파일이 있으면 ?v=수정 시각
        result.put("role", JwtUtil.roleOf(((Number) user.get("AUTH")).intValue()));
        result.put("createdAt", iso(created));
        return result;
    }
}
