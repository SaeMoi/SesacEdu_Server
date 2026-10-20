package com.example.sesacedu.mypage.cart;

import com.example.sesacedu.global.JwtUtil;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.example.sesacedu.global.ApiResponse.*;

/**
 * POST /mypage/cart/add (Notion API 명세서: 장바구니 추가).
 *
 * 처리 순서: 1. Authorization의 JWT 검증 (없음·위조 401 UNAUTHORIZED, 만료 401 TOKEN_EXPIRED)
 * → 2. 입력 검증 (courseId 필수·양의 정수, 아니면 400 INVALID_INPUT)
 * → 3. 강좌가 open이 아니면(없음·draft·closed 구분 없이) 404 COURSE_NOT_FOUND
 * → 4. 이미 수강 중이면 409 ALREADY_ENROLLED → 5. 장바구니 50개 이상이면 409 CART_FULL
 * → 6. INSERT, PK (user_id, Lid) 중복이면 409 ALREADY_IN_CART → 7. 201 CART_ITEM_ADDED { courseId, totalCount }.
 * 담는 대상은 토큰 sub로만 정한다. 본문에 price 등이 와도 무시한다 (cart에는 (user_id, Lid)만 저장).
 *
 * 의도된 취약점 (모의해킹용, 경미): ALREADY_ENROLLED / ALREADY_IN_CART로 내 보유 여부가 드러난다.
 */
@RestController
public class cart_add {

    private final JdbcTemplate jdbc;
    private final JwtUtil jwtUtil;

    public cart_add(JdbcTemplate jdbc, JwtUtil jwtUtil) {
        this.jdbc = jdbc;
        this.jwtUtil = jwtUtil;
    }

    @PostMapping("/mypage/cart/add")
    public ResponseEntity<Map<String, Object>> add(@RequestBody(required = false) AddRequest req,
                                                   HttpServletRequest request) {
        // 1. JWT 검증 (입력 검증보다 먼저)
        long userId = jwtUtil.getUid(request);
        if (req == null) req = new AddRequest();

        // 2. 입력 검증
        Long courseId = cart.positiveId(req.courseId);
        if (courseId == null) {
            List<Map<String, String>> errors = new ArrayList<>();
            errors.add(error("courseId", "양의 정수여야 합니다."));
            return invalidInput(errors);
        }

        // 3. 판매중(open) 강좌만
        Integer open = jdbc.queryForObject(
                "SELECT COUNT(*) FROM lectures WHERE Lid = ? AND status = 'open'", Integer.class, courseId);
        if (open == null || open == 0) {
            return ResponseEntity.status(404).body(body("COURSE_NOT_FOUND", "강좌를 찾을 수 없습니다.", null));
        }

        // 4. 이미 수강(구매)
        Integer enrolled = jdbc.queryForObject(
                "SELECT COUNT(*) FROM enrollments WHERE user_id = ? AND Lid = ?", Integer.class, userId, courseId);
        if (enrolled != null && enrolled > 0) {
            return ResponseEntity.status(409).body(body("ALREADY_ENROLLED", "이미 수강 중인 강좌입니다.", null));
        }

        // 5. 개수 제한
        if (cart.count(jdbc, userId) >= cart.MAX_COUNT) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("maxCount", cart.MAX_COUNT);
            return ResponseEntity.status(409).body(body("CART_FULL", "장바구니는 최대 50개까지 담을 수 있습니다.", result));
        }

        // 6. 담기. 중복은 PK가 막는다 (버튼 두 번 눌러도 하나만)
        try {
            jdbc.update("INSERT INTO cart (user_id, Lid) VALUES (?, ?)", userId, courseId);
        } catch (DuplicateKeyException e) {
            return ResponseEntity.status(409).body(body("ALREADY_IN_CART", "이미 장바구니에 담긴 강좌입니다.", null));
        }

        // 7. 응답
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("courseId", courseId);
        result.put("totalCount", cart.count(jdbc, userId));
        return ResponseEntity.status(201).body(body("CART_ITEM_ADDED", "장바구니에 담았습니다.", result));
    }

    /** 요청 본문 { courseId }. 형식 오류를 field 단위로 알려 주려고 Object로 받는다 */
    public static class AddRequest {
        public Object courseId;
    }
}
