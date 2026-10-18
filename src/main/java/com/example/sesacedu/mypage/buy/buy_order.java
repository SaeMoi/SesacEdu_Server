package com.example.sesacedu.mypage.buy;

import com.example.sesacedu.global.JwtUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static com.example.sesacedu.global.ApiResponse.*;

/**
 * POST /mypage/buy (Notion API 명세서: 결제 인터페이스 POST). 검증 게이트 + 주문 생성.
 *
 * 처리 순서: 1. Authorization의 JWT 검증 (없음·위조 401 UNAUTHORIZED, 만료 401 TOKEN_EXPIRED)
 * → 2. 본문 검증 (courseIds 양의 정수 1~50개, couponId null 또는 양의 정수, usePoint 0 이상 정수(기본 0),
 *      payMethod CARD | TRANSFER. 아니면 400 INVALID_INPUT)
 * → 3. buy.quote(): 409 CART_CHANGED / 400 COUPON_NOT_APPLICABLE (cpn·pnt 미리보기와 같은 계산)
 * → 4. usePoint 한도 초과면 400 INVALID_POINT
 * → 5-a. 최종 금액 1원 이상: 한 트랜잭션으로 orders(status 'pending') + order_items(주문 시점 Lname·price 스냅샷)
 *        → 201 ORDER_CREATED { orderId, orderName, amount, pay, summary }. 실제 승인은 POST /payment.
 *        쿠폰·포인트는 여기서 검증만 하고 차감하지 않는다 (결제창을 닫은 사용자의 쿠폰·포인트가 묶이지 않게).
 * → 5-b. 최종 0원: PG 없이 한 트랜잭션으로 orders(status 'paid', pay NULL) + order_items + 쿠폰 used = 1 + 포인트 차감
 *        + enrollments 등록 + 장바구니에서 삭제 → 201 ORDER_COMPLETED { orderId, summary, courseIds }.
 *
 * 포인트 저장 (Kim 결정 2026-10-13): orders에 use_point 컬럼이 없다. 주문당 쿠폰 1장, orders.discount = 쿠폰 할인액,
 * orders.Aprice = 실제 결제액이므로 포인트 사용액 = SUM(order_items.price) − discount − Aprice로 되찾는다.
 * ono는 "ORD-yyyyMMdd-{oid}" (KST). 금액은 모두 DB 값으로 서버가 계산하고 본문의 amount·price·discount는 무시한다.
 * 0원 완료의 쿠폰·포인트 차감은 조건부 UPDATE(used = 0, point >= 사용액)라 0행이면 롤백한다.
 * ERD 2026-10-13에 users.point가 없어 buy.POINTS_ENABLED = false: 잔액 0이라 usePoint가 0이 아니면 4번에서 INVALID_POINT,
 * 포인트 차감 UPDATE는 실행하지 않는다 (요청·응답 모양은 그대로).
 *
 * 의도된 취약점 없음 (기본 설계). 명세 「의도된 취약점」 후보: 요청 금액 신뢰(가격 조작), 할인 clamp 제거,
 * 포인트 lost update(결제 승인), 순차 oid 열거(결제 상세·영수증).
 */
@RestController
public class buy_order {

    private static final Logger log = LoggerFactory.getLogger(buy_order.class);
    private static final DateTimeFormatter ONO_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final Set<String> PAY = new LinkedHashSet<>();

    static {
        for (Map<String, String> m : buy.PAY_METHODS) PAY.add(m.get("code"));
    }

    private final JdbcTemplate jdbc;
    private final JwtUtil jwtUtil;
    private final TransactionTemplate tx;

    public buy_order(JdbcTemplate jdbc, JwtUtil jwtUtil, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.jwtUtil = jwtUtil;
        this.tx = tx;
    }

    @PostMapping("/mypage/buy")
    public ResponseEntity<Map<String, Object>> order(@RequestBody(required = false) OrderRequest req,
                                                     HttpServletRequest request) {
        // 1. JWT 검증 (입력 검증보다 먼저)
        long userId = jwtUtil.getUid(request);
        if (req == null) req = new OrderRequest();

        // 2. 본문 검증
        List<Map<String, String>> errors = new ArrayList<>();
        List<Long> ids = courseIds(req.courseIds);
        if (ids == null) errors.add(error("courseIds", "양의 정수 1~50개여야 합니다."));
        Long cid = null;
        if (req.couponId != null) {
            cid = positiveLong(req.couponId);
            if (cid == null) errors.add(error("couponId", "양의 정수여야 합니다."));
        }
        long usePoint = 0;
        if (req.usePoint != null) {
            Long p = nonNegativeLong(req.usePoint);
            if (p == null) errors.add(error("usePoint", "0 이상의 정수여야 합니다."));
            else usePoint = p;
        }
        if (!(req.payMethod instanceof String) || !PAY.contains(req.payMethod)) {
            errors.add(error("payMethod", "CARD 또는 TRANSFER여야 합니다."));
        }
        if (!errors.isEmpty()) return invalidInput(errors);

        // 3~4. 금액 계산 + 검증 (cpn·pnt와 같은 quote)
        // 바로 구매(direct: true)면 장바구니에 없는 강좌도 주문할 수 있다 (Kim 2026-10-18)
        boolean direct = Boolean.TRUE.equals(req.direct);
        buy.Quote q = buy.quote(jdbc, userId, ids, cid, direct);
        if (q.error != null) {
            log.info("order rejected user={} code={}", userId, q.error.getBody() == null ? null : q.error.getBody().get("code"));
            return q.error;
        }
        ResponseEntity<Map<String, Object>> invalidPoint = buy.pointError(q, usePoint);
        if (invalidPoint != null) {
            log.info("order rejected user={} code=INVALID_POINT usePoint={}", userId, usePoint);
            return invalidPoint;
        }
        long finalAmount = q.afterCoupon() - usePoint;
        Map<String, Object> summary = buy.summary(q.productAmount, q.couponDiscount, usePoint);
        Map<Long, String> names = names(ids);
        String pay = (String) req.payMethod;

        // 5-a. 결제할 금액이 있으면 pending 주문만 만든다
        if (finalAmount > 0) {
            final Long couponId = cid;
            long oid = Objects.requireNonNull(tx.execute(status -> insertOrder(userId, couponId, q, finalAmount, pay, "pending", names)));
            log.info("order created oid={} user={} amount={}", oid, userId, finalAmount);
            List<String> titles = new ArrayList<>();
            for (Long id : ids) titles.add(names.get(id));
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("orderId", oid);
            result.put("orderName", buy.orderName(titles));
            result.put("amount", finalAmount);
            result.put("pay", pay);
            result.put("summary", summary);
            return ResponseEntity.status(201).body(body("ORDER_CREATED", "주문이 생성되었습니다. 결제를 진행해주세요.", result));
        }

        // 5-b. 0원: 바로 완료. 하나라도 실패하면 전부 롤백
        final Long couponId = cid;
        final long point = usePoint;
        Object done = tx.execute(status -> {
            long oid = insertOrder(userId, couponId, q, 0, null, "paid", names);
            if (couponId != null && jdbc.update(
                    "UPDATE user_coupons SET used = 1, used_at = LOCALTIMESTAMP WHERE user_id = ? AND cid = ? AND used = 0",
                    userId, couponId) == 0) {
                status.setRollbackOnly();
                return "ALREADY_USED";
            }
            if (buy.POINTS_ENABLED && point > 0 && jdbc.update(
                    "UPDATE users SET point = point - ? WHERE user_id = ? AND point >= ?", point, userId, point) == 0) {
                status.setRollbackOnly();
                return "EXCEEDS_BALANCE";
            }
            try {
                for (Long id : ids) {
                    jdbc.update("INSERT INTO enrollments (user_id, Lid, done, enrolled_at) VALUES (?, ?, 0, LOCALTIMESTAMP)", userId, id);
                }
            } catch (DuplicateKeyException e) {
                status.setRollbackOnly();
                return "CART_CHANGED";
            }
            List<Object> args = new ArrayList<>();
            args.add(userId);
            args.addAll(ids);
            jdbc.update("DELETE FROM cart WHERE user_id = ? AND Lid IN ("
                    + String.join(", ", Collections.nCopies(ids.size(), "?")) + ")", args.toArray());
            return oid;
        });

        // 검증과 완료 사이에 동시 요청이 끼어든 경우
        if ("ALREADY_USED".equals(done)) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("reason", "ALREADY_USED");
            return ResponseEntity.badRequest().body(body("COUPON_NOT_APPLICABLE", "이 주문에 사용할 수 없는 쿠폰입니다.", result));
        }
        if ("EXCEEDS_BALANCE".equals(done)) {
            q.balance = buy.pointBalance(jdbc, userId);
            q.maxUsable = Math.min(q.balance, q.afterCoupon());
            return buy.pointError(q, point);
        }
        if ("CART_CHANGED".equals(done)) {
            return buy.cartChanged(ids, buy.orderPrices(jdbc, userId, ids, direct));
        }
        log.info("order completed (0 won) oid={} user={}", done, userId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("orderId", done);
        result.put("summary", summary);
        result.put("courseIds", ids);
        return ResponseEntity.status(201).body(body("ORDER_COMPLETED", "결제가 완료되었습니다.", result));
    }

    /** orders 1행 + order_items 스냅샷. 생성된 oid를 돌려준다 (트랜잭션 안에서 부른다) */
    private long insertOrder(long userId, Long couponId, buy.Quote q, long aprice, String pay, String status,
                             Map<Long, String> names) {
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO orders (user_id, cid, discount, Aprice, pay, status, created, paid_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?, LOCALTIMESTAMP, " + ("paid".equals(status) ? "LOCALTIMESTAMP" : "NULL") + ")",
                    new String[]{"OID"});
            ps.setLong(1, userId);
            ps.setObject(2, couponId);
            ps.setLong(3, q.couponDiscount);
            ps.setLong(4, aprice);
            ps.setString(5, pay);
            ps.setString(6, status);
            return ps;
        }, key);
        long oid = Objects.requireNonNull(key.getKey()).longValue();
        jdbc.update("UPDATE orders SET ono = ? WHERE oid = ?",
                "ORD-" + LocalDate.now(ZoneId.of("Asia/Seoul")).format(ONO_DATE) + "-" + oid, oid);
        for (Map.Entry<Long, Long> e : q.prices.entrySet()) {
            jdbc.update("INSERT INTO order_items (oid, Lid, Lname, price) VALUES (?, ?, ?, ?)",
                    oid, e.getKey(), names.get(e.getKey()), e.getValue());
        }
        return oid;
    }

    /** 주문 강좌의 현재 lectures.name (order_items.Lname 스냅샷·orderName용) */
    private Map<Long, String> names(List<Long> ids) {
        List<Object> args = new ArrayList<>(ids);
        Map<Long, String> names = new HashMap<>();
        for (Map<String, Object> r : jdbc.queryForList("SELECT Lid AS course_id, name AS title FROM lectures WHERE Lid IN ("
                + String.join(", ", Collections.nCopies(ids.size(), "?")) + ")", args.toArray())) {
            names.put(num(r.get("COURSE_ID")), (String) r.get("TITLE"));
        }
        return names;
    }

    /** JSON 배열 → 중복 제거한 양의 정수 목록, 비었거나 50개 초과·잘못된 원소면 null */
    private static List<Long> courseIds(Object v) {
        if (!(v instanceof List) || ((List<?>) v).isEmpty() || ((List<?>) v).size() > buy.MAX_COURSES) return null;
        Set<Long> ids = new LinkedHashSet<>();
        for (Object o : (List<?>) v) {
            Long id = positiveLong(o);
            if (id == null) return null;
            ids.add(id);
        }
        return new ArrayList<>(ids);
    }

    /** JSON 정수(Integer/Long)만, 1.5·"12"·true는 null */
    private static Long nonNegativeLong(Object v) {
        if (!(v instanceof Integer) && !(v instanceof Long)) return null;
        long n = ((Number) v).longValue();
        return n >= 0 ? n : null;
    }

    private static Long positiveLong(Object v) {
        Long n = nonNegativeLong(v);
        return n != null && n > 0 ? n : null;
    }

    /** 요청 본문 { courseIds, couponId, usePoint, payMethod, direct }. 형식 오류를 field 단위로 알려 주려고 Object로 받는다 */
    public static class OrderRequest {
        public Object courseIds;
        public Object couponId;
        public Object usePoint;
        public Object payMethod;
        /** true면 바로 구매 (장바구니 조건 없음). 생략·그 밖의 값은 장바구니 기준 */
        public Object direct;
    }
}
