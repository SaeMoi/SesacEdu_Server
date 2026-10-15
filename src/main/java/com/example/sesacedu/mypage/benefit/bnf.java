package com.example.sesacedu.mypage.benefit;

import com.example.sesacedu.global.JwtUtil;
import com.example.sesacedu.mypage.buy.buy;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.example.sesacedu.global.ApiResponse.*;

/**
 * GET /mypage/bnf?page=&size= (Notion API 명세서: 나의해택).
 *
 * 처리 순서: 1. Authorization의 JWT 검증 (없음·위조 401 UNAUTHORIZED, 만료 401 TOKEN_EXPIRED)
 * → 2. 쿼리 검증 (page 0 이상, size 1~50, 아니면 400 INVALID_INPUT)
 * → 3. 포인트 잔액 + 사용 가능 쿠폰 수 + 목록(만료 임박순) → 4. 200 BENEFITS_FOUND { summary, coupons, page }.
 * 조회 대상은 토큰 sub로만 정한다 (user_coupons.user_id = 나). 개인정보라 Cache-Control: no-store.
 * 사용 가능 = used = 0 AND due >= SYSTIMESTAMP. 사용·만료 쿠폰은 내리지 않고 상태 필드도 없다.
 * targetCourse는 coupons.Lid가 있으면 { courseId, title }, NULL(전체 강의)이면 null. 쿠폰 코드 원문은 내리지 않는다.
 *
 * pointBalance는 users.point (단일 잔액, 이력 없음). 회원 행이 없으면 0.
 * ERD 2026-10-13에 users.point가 없어 buy.POINTS_ENABLED = false인 동안은 항상 0 (필드는 그대로 둔다).
 * 의도된 취약점 없음. 포인트 lost update는 적립·사용(결제) 쪽, 할인 금액 범위 문제는 강사 쿠폰 발행·결제 쿠폰 적용에 둔다.
 */
@RestController
public class bnf {

    private final JdbcTemplate jdbc;
    private final JwtUtil jwtUtil;

    public bnf(JdbcTemplate jdbc, JwtUtil jwtUtil) {
        this.jdbc = jdbc;
        this.jwtUtil = jwtUtil;
    }

    @GetMapping("/mypage/bnf")
    public ResponseEntity<Map<String, Object>> list(@RequestParam(required = false) String page,
                                                    @RequestParam(required = false) String size,
                                                    HttpServletRequest request) {
        // 1. JWT 검증 (쿼리 검증보다 먼저)
        long userId = jwtUtil.getUid(request);

        // 2. 쿼리 검증. 빈 값은 기본값
        List<Map<String, String>> errors = new ArrayList<>();
        Integer pageNo = parseInt(page, 0);
        if (pageNo == null || pageNo < 0) errors.add(error("page", "0 이상의 정수여야 합니다."));
        Integer pageSize = parseInt(size, 10);
        if (pageSize == null || pageSize < 1 || pageSize > 50) errors.add(error("size", "1 이상 50 이하여야 합니다."));
        if (!errors.isEmpty()) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("errors", errors);
            return ResponseEntity.badRequest().body(body("INVALID_INPUT", "입력값을 확인해주세요.", result));
        }

        // 3-1. 포인트 잔액, 사용 가능(미사용·미만료) 쿠폰 수 = 목록 전체 건수
        long pointBalance = buy.pointBalance(jdbc, userId);
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_coupons uc JOIN coupons c ON c.cid = uc.cid"
                        + " WHERE uc.user_id = ? AND uc.used = 0 AND c.due >= SYSTIMESTAMP", Long.class, userId);
        long total = count == null ? 0 : count;

        // 3-2. 목록. 만료 임박순, 바인드 변수만 사용
        List<Map<String, Object>> rows = jdbc.queryForList(
                SELECT_COUPON
                        + " WHERE uc.user_id = ? AND uc.used = 0 AND c.due >= SYSTIMESTAMP"
                        + " ORDER BY c.due ASC, c.cid ASC"
                        + " OFFSET ? ROWS FETCH NEXT ? ROWS ONLY",
                userId, (long) pageNo * pageSize, pageSize);
        List<Map<String, Object>> coupons = new ArrayList<>();
        for (Map<String, Object> r : rows) coupons.add(couponItem(r));

        // 4. 응답
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("availableCouponCount", total);
        summary.put("pointBalance", pointBalance);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("summary", summary);
        result.put("coupons", coupons);
        result.put("page", pageInfo(pageNo, pageSize, total));
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(body("BENEFITS_FOUND", "보유 혜택을 조회했습니다.", result));
    }

    /** 내 쿠폰 한 장의 공통 SELECT (쿠폰등록 응답도 같은 형식). 뒤에 WHERE를 붙인다 */
    static final String SELECT_COUPON =
            "SELECT c.cid AS coupon_id, c.cname AS name, c.discount AS discount_value,"
                    + "       c.Lid AS target_course_id, l.name AS target_course_title, c.due AS expires_at"
                    + "  FROM user_coupons uc"
                    + "  JOIN coupons c ON c.cid = uc.cid"
                    + "  LEFT JOIN lectures l ON l.Lid = c.Lid";

    /** SELECT_COUPON 한 행 → coupons[] 항목 */
    static Map<String, Object> couponItem(Map<String, Object> r) {
        Map<String, Object> target = null;
        if (r.get("TARGET_COURSE_ID") != null) {
            target = new LinkedHashMap<>();
            target.put("courseId", num(r.get("TARGET_COURSE_ID")));
            target.put("title", r.get("TARGET_COURSE_TITLE"));
        }
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("couponId", num(r.get("COUPON_ID")));
        item.put("name", r.get("NAME"));
        item.put("discountValue", num(r.get("DISCOUNT_VALUE")));
        item.put("targetCourse", target);
        item.put("expiresAt", iso(r.get("EXPIRES_AT")));
        return item;
    }
}
