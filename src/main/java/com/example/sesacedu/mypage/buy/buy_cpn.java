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
 * GET /mypage/buy/cpn?courseIds=12,7&couponId=9001 (Notion API 명세서: 결제 쿠폰 적용).
 *
 * 처리 순서: 1. Authorization의 JWT 검증 (없음·위조 401 UNAUTHORIZED, 만료 401 TOKEN_EXPIRED)
 * → 2. 쿼리 검증 (courseIds 쉼표 구분 양의 정수 1~50개, couponId 양의 정수. 아니면 400 INVALID_INPUT)
 * → 3. 주문 대상 = 내 장바구니 + open + 미수강. 빠진 강좌가 있으면 409 CART_CHANGED { unavailableCourseIds }
 * → 4. 내 user_coupons에서 쿠폰 조회. 없음(남의 쿠폰 포함) COUPON_NOT_FOUND / 사용함 ALREADY_USED / 만료 EXPIRED /
 *      전용 강좌가 주문에 없음 TARGET_COURSE_NOT_IN_ORDER / 전체용인데 주문 합계 < min_amount MIN_AMOUNT_NOT_MET
 *      → 400 COUPON_NOT_APPLICABLE { reason } (MIN_AMOUNT_NOT_MET이면 minAmount도 같이)
 * → 5. 200 COUPON_APPLIED { summary, appliedCouponId }.
 * 조회 전용 미리보기: user_coupons.used를 바꾸지 않고 어떤 테이블에도 쓰지 않는다. 개인정보라 Cache-Control: no-store.
 *
 * 할인액 = LEAST(coupons.discount, 적용 대상 금액)을 0 아래로 내려가지 않게 자른다 (전체용은 주문 합계, 전용은 그 강좌 가격).
 * 명세 예시의 「쿠폰 없이 조회」(appliedCouponId null)는 couponId 필수 규칙과 어긋나 400으로 둔다.
 * coupons.min_amount는 전체용 쿠폰(Lid NULL)에만 적용한다 (Kim 결정 2026-10-13). 강좌 전용 쿠폰은 보지 않는다.
 * 의도된 취약점 없음. 할인 범위 악용·최종 금액 조작은 POST /mypage/buy와 결제 승인에 둔다.
 */
@RestController
public class buy_cpn {

    private final JdbcTemplate jdbc;
    private final JwtUtil jwtUtil;

    public buy_cpn(JdbcTemplate jdbc, JwtUtil jwtUtil) {
        this.jdbc = jdbc;
        this.jwtUtil = jwtUtil;
    }

    @GetMapping("/mypage/buy/cpn")
    public ResponseEntity<Map<String, Object>> apply(@RequestParam(required = false) String courseIds,
                                                     @RequestParam(required = false) String couponId,
                                                     HttpServletRequest request) {
        // 1. JWT 검증 (쿼리 검증보다 먼저)
        long userId = jwtUtil.getUid(request);

        // 2. 쿼리 검증
        List<Map<String, String>> errors = new ArrayList<>();
        List<Long> ids = buy.parseCourseIds(courseIds);
        if (ids == null) errors.add(error("courseIds", "쉼표로 구분한 양의 정수 1~50개여야 합니다."));
        Long cid = buy.parsePositive(couponId);
        if (cid == null) errors.add(error("couponId", "양의 정수여야 합니다."));
        if (!errors.isEmpty()) return invalidInput(errors);

        // 3~4. 주문 대상 + 쿠폰 검증·할인 (pnt·POST와 같은 계산)
        buy.Quote q = buy.quote(jdbc, userId, ids, cid);
        if (q.error != null) return q.error;

        // 5. 금액 계산만 (DB 변경 없음)
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("summary", buy.summary(q.productAmount, q.couponDiscount, 0));
        result.put("appliedCouponId", cid);
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(body("COUPON_APPLIED", "쿠폰을 적용했습니다.", result));
    }
}
