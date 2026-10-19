package com.example.sesacedu.mypage.buy;

import com.example.sesacedu.global.JwtUtil;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.example.sesacedu.global.ApiResponse.*;

/**
 * GET /mypage/buy (Notion API 명세서: 결제 인터페이스 GET).
 *
 * 처리 순서: 1. Authorization의 JWT 검증 (없음·위조 401 UNAUTHORIZED, 만료 401 TOKEN_EXPIRED)
 * → 2. 내 cart + lectures 조인 전체 (Lid DESC, open 아니면 purchasable false)
 * → 3. 내 미사용·미만료 쿠폰 (discount DESC), 장바구니의 구매 가능 항목과 대조해 applicable·expectedDiscount
 * → 4. users.point 잔액, maxUsable = LEAST(잔액, productAmount). 지금은 POINTS_ENABLED = false라 둘 다 0
 * → 5. 200 CHECKOUT_ITEMS_FOUND { items, itemCount, coupons, point, orderName, payMethods, summary }.
 * 조회 전용 (주문·차감 없음). 대상은 토큰 sub로만 정한다 (IDOR 없음). 개인정보라 Cache-Control: no-store.
 *
 * 바로 구매 (Kim 2026-10-18): ?courseIds=12&direct=true 이면 장바구니 대신 그 강좌를 대상으로 한다
 * (장바구니에 없어도 됨, 장바구니는 바꾸지 않음). open이고 아직 수강하지 않은 강좌만 purchasable true.
 * cpn·pnt·POST /mypage/buy도 같은 direct 값을 받아 장바구니 없이 계산한다.
 *
 * itemCount·productAmount·orderName은 구매 가능(open) 항목만 센다. 쿠폰 모양은 GET /mypage/bnf의 coupons[]와 같고
 * applicable·expectedDiscount만 더한다. summary의 couponDiscountAmount·usePoint는 여기서 항상 0
 * (쿠폰은 GET /mypage/buy/cpn, 포인트는 GET /mypage/buy/pnt가 반영).
 * coupons.min_amount(적용 최소 금액, Kim 결정 2026-10-13)는 전체용 쿠폰(Lid NULL)에만 적용한다:
 * productAmount < min_amount면 applicable false. 강좌 전용 쿠폰은 min_amount가 있어도 보지 않는다.
 * 의도된 취약점 없음. 가격·쿠폰·포인트 조작은 POST /mypage/buy와 결제 승인에 둔다.
 */
@RestController
public class buy {

    /** 한 주문의 최대 강좌 수 (courseIds 1~50개) */
    static final int MAX_COURSES = 50;

    /** 결제 수단 화이트리스트 (POST /mypage/buy의 payMethod) */
    static final List<Map<String, String>> PAY_METHODS;

    static {
        List<Map<String, String>> m = new ArrayList<>();
        m.add(payMethod("CARD", "카드"));
        m.add(payMethod("TRANSFER", "계좌이체"));
        PAY_METHODS = Collections.unmodifiableList(m);
    }

    private final JdbcTemplate jdbc;
    private final JwtUtil jwtUtil;

    public buy(JdbcTemplate jdbc, JwtUtil jwtUtil) {
        this.jdbc = jdbc;
        this.jwtUtil = jwtUtil;
    }

    @GetMapping("/mypage/buy")
    public ResponseEntity<Map<String, Object>> checkout(@RequestParam(required = false) String courseIds,
                                                        @RequestParam(required = false) String direct,
                                                        HttpServletRequest request) {
        // 1. JWT 검증
        long userId = jwtUtil.getUid(request);

        // 2. 결제 대상. 기본은 내 장바구니 전체, 바로 구매(direct=true)면 courseIds의 강좌. 바인드 변수만 사용
        List<Map<String, Object>> rows;
        if (isDirect(direct)) {
            List<Long> ids = parseCourseIds(courseIds);
            if (ids == null) {
                List<Map<String, String>> errors = new ArrayList<>();
                errors.add(error("courseIds", "쉼표로 구분한 양의 정수 1~50개여야 합니다."));
                return invalidInput(errors);
            }
            List<Object> args = new ArrayList<>();
            args.add(userId);
            args.addAll(ids);
            rows = jdbc.queryForList(
                    "SELECT l.Lid AS course_id, l.name AS title, l.Lprice AS price,"
                            + "       CASE WHEN l.status = 'open' AND NOT EXISTS"
                            + "            (SELECT 1 FROM enrollments e WHERE e.user_id = ? AND e.Lid = l.Lid)"
                            + "            THEN 1 ELSE 0 END AS purchasable"
                            + "  FROM lectures l"
                            + " WHERE l.Lid IN (" + String.join(", ", Collections.nCopies(ids.size(), "?")) + ")"
                            + " ORDER BY l.Lid DESC", args.toArray());
        } else {
            rows = jdbc.queryForList(
                    "SELECT c.Lid AS course_id, l.name AS title, l.Lprice AS price,"
                            + "       CASE WHEN l.status = 'open' THEN 1 ELSE 0 END AS purchasable"
                            + "  FROM cart c JOIN lectures l ON l.Lid = c.Lid"
                            + " WHERE c.user_id = ?"
                            + " ORDER BY c.Lid DESC", userId);
        }

        List<Map<String, Object>> items = new ArrayList<>();
        Map<Long, Long> open = new LinkedHashMap<>();      // 구매 가능 항목 Lid → 가격 (쿠폰 대조용)
        List<String> openTitles = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            long courseId = num(r.get("COURSE_ID"));
            long price = num(r.get("PRICE"));
            boolean purchasable = num(r.get("PURCHASABLE")) == 1;
            if (purchasable) {
                open.put(courseId, price);
                openTitles.add((String) r.get("TITLE"));
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("courseId", courseId);
            item.put("title", r.get("TITLE"));
            item.put("price", price);
            item.put("purchasable", purchasable);
            items.add(item);
        }
        long productAmount = sum(open.values());

        // 3. 쓸 수 있는 쿠폰 (내 미사용·미만료). 금액 반영은 안 하고 표시용 값만
        List<Map<String, Object>> couponRows = jdbc.queryForList(
                "SELECT c.cid AS coupon_id, c.cname AS name, c.discount AS discount_value,"
                        + "       c.Lid AS target_course_id, l.name AS target_course_title, c.due AS expires_at,"
                        + "       c.min_amount AS min_amount"
                        + "  FROM user_coupons uc"
                        + "  JOIN coupons c ON c.cid = uc.cid"
                        + "  LEFT JOIN lectures l ON l.Lid = c.Lid"
                        + " WHERE uc.user_id = ? AND uc.used = 0 AND c.due >= SYSTIMESTAMP"
                        + " ORDER BY c.discount DESC, c.cid ASC", userId);
        List<Map<String, Object>> coupons = new ArrayList<>();
        for (Map<String, Object> r : couponRows) {
            Map<String, Object> target = null;
            Long targetId = r.get("TARGET_COURSE_ID") == null ? null : num(r.get("TARGET_COURSE_ID"));
            if (targetId != null) {
                target = new LinkedHashMap<>();
                target.put("courseId", targetId);
                target.put("title", r.get("TARGET_COURSE_TITLE"));
            }
            boolean applicable = !open.isEmpty() && (targetId == null || open.containsKey(targetId))
                    && meetsMinAmount(targetId, r.get("MIN_AMOUNT"), productAmount);
            long discountValue = num(r.get("DISCOUNT_VALUE"));
            Map<String, Object> coupon = new LinkedHashMap<>();
            coupon.put("couponId", num(r.get("COUPON_ID")));
            coupon.put("name", r.get("NAME"));
            coupon.put("discountValue", discountValue);
            coupon.put("targetCourse", target);
            coupon.put("expiresAt", iso(r.get("EXPIRES_AT")));
            coupon.put("applicable", applicable);
            coupon.put("expectedDiscount", applicable ? couponDiscount(discountValue, targetId, open) : 0L);
            coupons.add(coupon);
        }

        // 4. 포인트 잔액. 회원 행이 없으면 0
        long balance = pointBalance(jdbc, userId);
        Map<String, Object> point = new LinkedHashMap<>();
        point.put("balance", balance);
        point.put("maxUsable", Math.min(balance, productAmount));

        // 5. 응답
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", items);
        result.put("itemCount", open.size());
        result.put("coupons", coupons);
        result.put("point", point);
        result.put("orderName", orderName(openTitles));
        result.put("payMethods", PAY_METHODS);
        result.put("summary", summary(productAmount, 0, 0));
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(body("CHECKOUT_ITEMS_FOUND", "결제할 강의를 조회했습니다.", result));
    }

    /** 바로 구매 여부. "true"(대소문자 무시)만 바로 구매, 그 밖의 값·생략은 장바구니 기준 */
    static boolean isDirect(String v) {
        return v != null && "true".equalsIgnoreCase(v.trim());
    }

    /**
     * 쿼리 courseIds "12,7" → 중복 제거한 Lid 목록 (입력 순서 유지). 비었거나, 50개 초과,
     * 양의 정수가 아닌 값이 있으면 null.
     */
    static List<Long> parseCourseIds(String v) {
        if (isBlank(v)) return null;
        Set<Long> ids = new LinkedHashSet<>();
        String[] parts = v.split(",", -1);
        if (parts.length > MAX_COURSES) return null;
        for (String p : parts) {
            Long id = parsePositive(p);
            if (id == null) return null;
            ids.add(id);
        }
        return new ArrayList<>(ids);
    }

    /** 양의 정수 문자열만 (공백 허용, "1.5"·"-1"·"0"·"x"는 null) */
    static Long parsePositive(String v) {
        Long n = parseNonNegative(v);
        return n != null && n > 0 ? n : null;
    }

    /** 0 이상 정수 문자열만 (공백 허용, "1.5"·"-1"·"x"는 null) */
    static Long parseNonNegative(String v) {
        if (v == null || !v.trim().matches("[0-9]{1,15}")) return null;
        return Long.parseLong(v.trim());
    }

    /**
     * 주문 대상 = 내 장바구니 + open + 미수강 인 강좌만 Lid → Lprice (입력 순서).
     * 요청한 courseIds 중 여기서 빠진 것이 있으면 409 CART_CHANGED. IN 목록은 자리표시자로 바인드한다.
     */
    static Map<Long, Long> orderPrices(JdbcTemplate jdbc, long userId, List<Long> courseIds) {
        return orderPrices(jdbc, userId, courseIds, false);
    }

    /** direct = true(바로 구매)면 장바구니 조건 없이 open + 미수강 강좌만 */
    static Map<Long, Long> orderPrices(JdbcTemplate jdbc, long userId, List<Long> courseIds, boolean direct) {
        String placeholders = String.join(", ", Collections.nCopies(courseIds.size(), "?"));
        List<Object> args = new ArrayList<>();
        if (!direct) args.add(userId);
        args.addAll(courseIds);
        args.add(userId);
        String sql = direct
                ? "SELECT l.Lid AS course_id, l.Lprice AS price"
                        + "  FROM lectures l"
                        + " WHERE l.Lid IN (" + placeholders + ") AND l.status = 'open'"
                        + "   AND NOT EXISTS (SELECT 1 FROM enrollments e WHERE e.user_id = ? AND e.Lid = l.Lid)"
                : "SELECT l.Lid AS course_id, l.Lprice AS price"
                        + "  FROM cart c JOIN lectures l ON l.Lid = c.Lid"
                        + " WHERE c.user_id = ? AND c.Lid IN (" + placeholders + ") AND l.status = 'open'"
                        + "   AND NOT EXISTS (SELECT 1 FROM enrollments e WHERE e.user_id = ? AND e.Lid = c.Lid)";
        Map<Long, Long> found = new HashMap<>();
        for (Map<String, Object> r : jdbc.queryForList(sql, args.toArray())) {
            found.put(num(r.get("COURSE_ID")), num(r.get("PRICE")));
        }
        Map<Long, Long> prices = new LinkedHashMap<>();
        for (Long id : courseIds) {
            if (found.containsKey(id)) prices.put(id, found.get(id));
        }
        return prices;
    }

    /**
     * 주문 금액 계산 결과. cpn / pnt 미리보기와 POST /mypage/buy가 같은 quote()를 써서 금액이 어긋나지 않게 한다.
     * error가 있으면 그대로 응답한다 (409 CART_CHANGED, 400 COUPON_NOT_APPLICABLE).
     */
    static final class Quote {
        ResponseEntity<Map<String, Object>> error;
        Map<Long, Long> prices;          // 주문 강좌 Lid → 현재 Lprice (요청 순서)
        long productAmount;
        long couponDiscount;             // 쿠폰 없으면 0
        long balance;                    // users.point (POINTS_ENABLED = false면 0)
        long maxUsable;                  // LEAST(balance, 쿠폰 적용 후 금액)

        long afterCoupon() {
            return productAmount - couponDiscount;
        }
    }

    /**
     * 금액 계산 (1) 주문 대상 = 내 장바구니 + open + 미수강, 빠지면 409 CART_CHANGED
     * → (2) couponId가 있으면 내 쿠폰 검증 + 할인액 clamp, 불가면 400 COUPON_NOT_APPLICABLE
     * → (3) 포인트 잔액과 사용 한도. 포인트 사용액 검증은 pointError()에서.
     * 값은 모두 DB에서 읽는다 (요청의 금액은 받지 않는다). 어떤 테이블도 바꾸지 않는다.
     */
    static Quote quote(JdbcTemplate jdbc, long userId, List<Long> courseIds, Long couponId) {
        return quote(jdbc, userId, courseIds, couponId, false);
    }

    /** direct = true(바로 구매)면 주문 대상에 장바구니 조건을 두지 않는다 */
    static Quote quote(JdbcTemplate jdbc, long userId, List<Long> courseIds, Long couponId, boolean direct) {
        Quote q = new Quote();
        q.prices = orderPrices(jdbc, userId, courseIds, direct);
        if (q.prices.size() < courseIds.size()) {
            q.error = cartChanged(courseIds, q.prices);
            return q;
        }
        q.productAmount = sum(q.prices.values());

        if (couponId != null) {
            // 항상 user_id = 나 조건으로 찾는다 (남의 cid도 없음)
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT c.discount AS discount, c.Lid AS target_course_id, uc.used AS used, c.min_amount AS min_amount,"
                            + "       CASE WHEN c.due >= SYSTIMESTAMP THEN 0 ELSE 1 END AS expired"
                            + "  FROM user_coupons uc JOIN coupons c ON c.cid = uc.cid"
                            + " WHERE uc.user_id = ? AND uc.cid = ?", userId, couponId);
            Map<String, Object> coupon = rows.isEmpty() ? null : rows.get(0);
            Long targetId = coupon == null || coupon.get("TARGET_COURSE_ID") == null
                    ? null : num(coupon.get("TARGET_COURSE_ID"));
            String reason = null;
            if (coupon == null) reason = "COUPON_NOT_FOUND";
            else if (num(coupon.get("USED")) != 0) reason = "ALREADY_USED";
            else if (num(coupon.get("EXPIRED")) != 0) reason = "EXPIRED";
            else if (targetId != null && !q.prices.containsKey(targetId)) reason = "TARGET_COURSE_NOT_IN_ORDER";
            else if (!meetsMinAmount(targetId, coupon.get("MIN_AMOUNT"), q.productAmount)) reason = "MIN_AMOUNT_NOT_MET";
            if (reason != null) {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("reason", reason);
                if ("MIN_AMOUNT_NOT_MET".equals(reason)) result.put("minAmount", num(coupon.get("MIN_AMOUNT")));
                q.error = ResponseEntity.badRequest()
                        .body(body("COUPON_NOT_APPLICABLE", "이 주문에 사용할 수 없는 쿠폰입니다.", result));
                return q;
            }
            q.couponDiscount = couponDiscount(num(coupon.get("DISCOUNT")), targetId, q.prices);
        }

        q.balance = pointBalance(jdbc, userId);
        q.maxUsable = Math.min(q.balance, q.afterCoupon());
        return q;
    }

    /** usePoint가 한도를 넘으면 400 INVALID_POINT { maxUsablePoint, balance, reason }, 아니면 null */
    static ResponseEntity<Map<String, Object>> pointError(Quote q, long usePoint) {
        if (usePoint <= q.maxUsable) return null;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("maxUsablePoint", q.maxUsable);
        result.put("balance", q.balance);
        result.put("reason", usePoint > q.balance ? "EXCEEDS_BALANCE" : "EXCEEDS_ORDER_AMOUNT");
        return ResponseEntity.badRequest().body(body("INVALID_POINT", "사용할 수 있는 포인트를 초과했습니다.", result));
    }

    /**
     * 포인트 기능 스위치. ERD 2026-10-13에 users.point가 없어(「포인트 잔액·차감은 결제 계산 정책을 정한 뒤 반영」) 꺼 둔다.
     * 꺼져 있으면 잔액은 항상 0이라 usePoint가 0이 아니면 기존 400 INVALID_POINT(EXCEEDS_BALANCE)로 막히고,
     * 응답 모양(point 블록, summary.usePoint, pointBalance)은 그대로 둔다.
     * 다시 켤 때: users.point 컬럼을 schema.sql·schema-test.sql에 되살리고 이 값을 true로.
     */
    public static final boolean POINTS_ENABLED = false;

    /** users.point. 회원 행이 없거나 포인트 기능이 꺼져 있으면 0 */
    public static long pointBalance(JdbcTemplate jdbc, long userId) {
        if (!POINTS_ENABLED) return 0;
        List<Long> points = jdbc.queryForList("SELECT point FROM users WHERE user_id = ?", Long.class, userId);
        return points.isEmpty() || points.get(0) == null ? 0 : points.get(0);
    }

    /** 409 CART_CHANGED { unavailableCourseIds } */
    static ResponseEntity<Map<String, Object>> cartChanged(List<Long> courseIds, Map<Long, Long> prices) {
        List<Long> unavailable = new ArrayList<>();
        for (Long id : courseIds) {
            if (!prices.containsKey(id)) unavailable.add(id);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("unavailableCourseIds", unavailable);
        return ResponseEntity.status(409).body(body("CART_CHANGED", "주문할 수 없는 강의가 포함되어 있습니다.", result));
    }

    /**
     * 쿠폰 할인액 = discount를 적용 대상 금액으로 자른 값 (0 ~ 대상 금액).
     * 대상 금액은 전체용(targetId null)이면 주문 합계, 전용이면 그 강좌 가격. 주문에 없으면 0.
     */
    static long couponDiscount(long discount, Long targetId, Map<Long, Long> orderPrices) {
        long base = targetId == null ? sum(orderPrices.values()) : orderPrices.getOrDefault(targetId, 0L);
        return Math.max(0, Math.min(discount, base));
    }

    /** 최소 금액 조건. 전체용 쿠폰(targetId null)이고 min_amount가 있을 때만 주문 합계와 비교한다 */
    static boolean meetsMinAmount(Long targetId, Object minAmount, long productAmount) {
        return targetId != null || minAmount == null || productAmount >= num(minAmount);
    }

    /** 공통 summary { productAmount, couponDiscountAmount, usePoint, finalAmount } */
    static Map<String, Object> summary(long productAmount, long couponDiscountAmount, long usePoint) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("productAmount", productAmount);
        s.put("couponDiscountAmount", couponDiscountAmount);
        s.put("usePoint", usePoint);
        s.put("finalAmount", productAmount - couponDiscountAmount - usePoint);
        return s;
    }

    /** 1개면 강좌명, 여러 개면 "첫 강좌명 외 N개", 없으면 null (저장 안 함) */
    static String orderName(List<String> titles) {
        if (titles.isEmpty()) return null;
        return titles.size() == 1 ? titles.get(0) : titles.get(0) + " 외 " + (titles.size() - 1) + "개";
    }

    static long sum(Collection<Long> prices) {
        long total = 0;
        for (long p : prices) total += p;
        return total;
    }

    private static Map<String, String> payMethod(String code, String label) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("code", code);
        m.put("label", label);
        return m;
    }
}
