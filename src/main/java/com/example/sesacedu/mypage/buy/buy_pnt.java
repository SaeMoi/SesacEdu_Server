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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.example.sesacedu.global.ApiResponse.*;

/**
 * GET /mypage/buy/pnt?courseIds=12,7&couponId=9001&usePoint=3000 (Notion API 명세서: 결제 포인트 적용).
 *
 * 처리 순서: 1. Authorization의 JWT 검증 (없음·위조 401 UNAUTHORIZED, 만료 401 TOKEN_EXPIRED)
 * → 2. 쿼리 검증 (courseIds 쉼표 구분 양의 정수 1~50개, usePoint 0 이상 정수 필수, couponId는 있으면 양의 정수.
 *      아니면 400 INVALID_INPUT)
 * → 3. 주문 대상 = 내 장바구니 + open + 미수강, 빠지면 409 CART_CHANGED
 * → 4. couponId가 있으면 GET /mypage/buy/cpn과 같은 검증 (400 COUPON_NOT_APPLICABLE)
 * → 5. maxUsable = LEAST(users.point, 쿠폰 적용 후 금액). 넘으면 400 INVALID_POINT
 *      (ERD 2026-10-13에 users.point가 없어 buy.POINTS_ENABLED = false: 잔액 0이라 usePoint 0만 200)
 *      { maxUsablePoint, balance, reason: EXCEEDS_BALANCE | EXCEEDS_ORDER_AMOUNT }
 * → 6. 200 POINT_APPLIED { summary, point }.
 * 조회 전용 미리보기: users.point·user_coupons를 바꾸지 않는다. 개인정보라 Cache-Control: no-store.
 * 금액 계산은 POST /mypage/buy와 같은 buy.quote()를 쓴다.
 *
 * 의도된 취약점 없음. users.point 단일 잔액의 lost update는 결제 승인 쪽에 둔다.
 */
@RestController
public class buy_pnt {

    private final JdbcTemplate jdbc;
    private final JwtUtil jwtUtil;

    public buy_pnt(JdbcTemplate jdbc, JwtUtil jwtUtil) {
        this.jdbc = jdbc;
        this.jwtUtil = jwtUtil;
    }

    @GetMapping("/mypage/buy/pnt")
    public ResponseEntity<Map<String, Object>> apply(@RequestParam(required = false) String courseIds,
                                                     @RequestParam(required = false) String couponId,
                                                     @RequestParam(required = false) String direct,
                                                     @RequestParam(required = false) String usePoint,
                                                     HttpServletRequest request) {
        // 1. JWT 검증 (쿼리 검증보다 먼저)
        long userId = jwtUtil.getUid(request);

        // 2. 쿼리 검증. couponId는 비어 있으면 쿠폰 미적용
        List<Map<String, String>> errors = new ArrayList<>();
        List<Long> ids = buy.parseCourseIds(courseIds);
        if (ids == null) errors.add(error("courseIds", "쉼표로 구분한 양의 정수 1~50개여야 합니다."));
        Long cid = null;
        if (!isBlank(couponId)) {
            cid = buy.parsePositive(couponId);
            if (cid == null) errors.add(error("couponId", "양의 정수여야 합니다."));
        }
        Long point = buy.parseNonNegative(usePoint);
        if (point == null) errors.add(error("usePoint", "0 이상의 정수여야 합니다."));
        if (!errors.isEmpty()) return invalidInput(errors);

        // 3~5. 주문 대상 + 쿠폰 + 포인트 한도 (POST /mypage/buy와 같은 계산)
        buy.Quote q = buy.quote(jdbc, userId, ids, cid, buy.isDirect(direct));
        if (q.error != null) return q.error;
        ResponseEntity<Map<String, Object>> invalidPoint = buy.pointError(q, point);
        if (invalidPoint != null) return invalidPoint;

        // 6. 금액 계산만 (DB 변경 없음)
        Map<String, Object> pointInfo = new LinkedHashMap<>();
        pointInfo.put("balance", q.balance);
        pointInfo.put("maxUsable", q.maxUsable);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("summary", buy.summary(q.productAmount, q.couponDiscount, point));
        result.put("point", pointInfo);
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(body("POINT_APPLIED", "포인트를 적용했습니다.", result));
    }
}
