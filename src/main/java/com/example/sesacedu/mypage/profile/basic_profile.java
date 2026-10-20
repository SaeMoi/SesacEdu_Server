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
 * GET /mypage/basic_profile (기본 프로필: 마이페이지 사이드바 카드용).
 *
 * GET /mypage 의 가벼운 버전. 화면 구석에 이름·사진·권한·포인트만 보여줄 때 쓴다.
 * 이메일·휴대폰·생년월일 같은 개인정보는 내려주지 않는다 (필요하면 GET /mypage).
 *
 * 처리 순서: 1. Authorization의 JWT 검증 (없음·위조 401 UNAUTHORIZED, 만료 401 TOKEN_EXPIRED)
 * → 2. 토큰 sub(user_id)로 users 조회, 없으면 404 MEMBER_NOT_FOUND → 3. 200 BASIC_PROFILE_FOUND.
 * 조회 대상은 토큰 sub로만 정한다 (경로·쿼리에 회원 ID를 받지 않음 = IDOR 방지). Cache-Control: no-store.
 * point = users.point (관리자 지급·시드로만 생김, 2026-10-19).
 */
@RestController
public class basic_profile {

    private final JdbcTemplate jdbc;
    private final JwtUtil jwtUtil;
    private final ProfileImageStore images;

    public basic_profile(JdbcTemplate jdbc, JwtUtil jwtUtil, ProfileImageStore images) {
        this.jdbc = jdbc;
        this.jwtUtil = jwtUtil;
        this.images = images;
    }

    @GetMapping("/mypage/basic_profile")
    public ResponseEntity<Map<String, Object>> getBasicProfile(HttpServletRequest request) {
        // 1. JWT 검증
        long userId = jwtUtil.getUid(request);

        // 2. 회원 조회 (바인드 변수만 사용)
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT user_id, uname, nickname, picture, auth, point FROM users WHERE user_id = ?", userId);
        if (rows.isEmpty()) {
            return ResponseEntity.status(404).header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .body(body("MEMBER_NOT_FOUND", "회원 정보를 찾을 수 없습니다.", null));
        }
        Map<String, Object> user = rows.get(0);

        // 3. 응답
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("memberId", ((Number) user.get("USER_ID")).longValue());
        result.put("name", user.get("UNAME"));
        result.put("nickname", user.get("NICKNAME"));
        result.put("profileImageUrl", images.urlOf((String) user.get("PICTURE"))); // 파일이 있으면 ?v=수정 시각
        result.put("role", JwtUtil.roleOf(((Number) user.get("AUTH")).intValue()));
        Object point = user.get("POINT");
        result.put("point", point == null ? 0L : ((Number) point).longValue());
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(body("BASIC_PROFILE_FOUND", "기본 프로필을 조회했습니다.", result));
    }
}
