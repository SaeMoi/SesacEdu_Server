package com.example.sesacedu.mypage.cart;

import com.example.sesacedu.global.JwtUtil;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.example.sesacedu.global.ApiResponse.*;

/**
 * GET /mypage/cart (Notion API 명세서: 장바구니).
 *
 * 처리 순서: 1. Authorization의 JWT 검증 (없음·위조 401 UNAUTHORIZED, 만료 401 TOKEN_EXPIRED)
 * → 2. 내 cart + lectures 조인 목록, 항목 수·가격 합계 → 3. 200 CART_FOUND (비어 있어도 200, items: []).
 * 조회 대상은 토큰 sub로만 정한다 (cart.user_id = 나, IDOR 없음). 개인정보라 Cache-Control: no-store.
 *
 * 가격은 cart에 저장하지 않고 조회 시점 lectures.Lprice를 쓴다. purchasable = lectures.status가 'open'.
 * 정렬은 명세 확정대로 Lid DESC (schema.sql에는 cart.created가 있지만 명세 결정을 따른다).
 * 표기는 ERD(uid/Lid)지만 실제 회원 컬럼명은 user_id (schema.sql).
 * 의도된 취약점 없음. 가격 조작은 결제(POST /mypage/buy)에 둔다.
 */
@RestController
public class cart {

    /** 회원당 장바구니 최대 개수 (POST /mypage/cart/add에서 제한) */
    static final int MAX_COUNT = 50;

    private final JdbcTemplate jdbc;
    private final JwtUtil jwtUtil;

    public cart(JdbcTemplate jdbc, JwtUtil jwtUtil) {
        this.jdbc = jdbc;
        this.jwtUtil = jwtUtil;
    }

    @GetMapping("/mypage/cart")
    public ResponseEntity<Map<String, Object>> list(HttpServletRequest request) {
        // 1. JWT 검증
        long userId = jwtUtil.getUid(request);

        // 2. 목록. 바인드 변수만 사용
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT c.Lid AS course_id, l.name AS title, l.Lprice AS price,"
                        + "       CASE WHEN l.status = 'open' THEN 1 ELSE 0 END AS purchasable"
                        + "  FROM cart c JOIN lectures l ON l.Lid = c.Lid"
                        + " WHERE c.user_id = ?"
                        + " ORDER BY c.Lid DESC", userId);

        List<Map<String, Object>> items = new ArrayList<>();
        long productAmount = 0;
        for (Map<String, Object> r : rows) {
            long price = num(r.get("PRICE"));
            productAmount += price;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("courseId", num(r.get("COURSE_ID")));
            item.put("title", r.get("TITLE"));
            item.put("price", price);
            item.put("purchasable", num(r.get("PURCHASABLE")) == 1);
            items.add(item);
        }

        // 3. 응답. 합계는 표시용이며 실제 결제액은 결제 API가 다시 계산
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("itemCount", items.size());
        summary.put("productAmount", productAmount);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", items);
        result.put("summary", summary);
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(body("CART_FOUND", "장바구니를 조회했습니다.", result));
    }

    /** 내 장바구니 항목 수 (담기·삭제 응답의 totalCount) */
    static long count(JdbcTemplate jdbc, long userId) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM cart WHERE user_id = ?", Long.class, userId);
        return n == null ? 0 : n;
    }

    /** JSON 숫자 중 양의 정수만 (1.5, "12", true 등은 null) */
    static Long positiveId(Object v) {
        if (!(v instanceof Integer) && !(v instanceof Long)) return null;
        long n = ((Number) v).longValue();
        return n > 0 ? n : null;
    }
}
