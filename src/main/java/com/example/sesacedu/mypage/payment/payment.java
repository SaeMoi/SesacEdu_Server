package com.example.sesacedu.mypage.payment;

import com.example.sesacedu.global.JwtUtil;
import com.example.sesacedu.mypage.buy.buy;
import com.example.sesacedu.virtualpg.VpgClient;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.example.sesacedu.global.ApiResponse.*;

/**
 * POST /payment (Notion API 명세서: 결제). POST /mypage/buy가 만든 pending 주문을 가상 PG로 승인받고 마무리한다.
 *
 * 처리 순서: 1. Authorization의 JWT 검증 (없음·위조 401 UNAUTHORIZED, 만료 401 TOKEN_EXPIRED)
 * → 2. orderId 검증 (양의 정수, 아니면 400 INVALID_INPUT)
 * → 3. 트랜잭션 시작. 내 주문 잠금 SELECT ... FOR UPDATE (없음·남의 주문 404 ORDER_NOT_FOUND, pending 아님 409 ALREADY_PAID)
 * → 4. 결제 정보 검증: orders.pay(CARD/TRANSFER)에 맞는 card/transfer만, 형식 오류·불일치 400 INVALID_INPUT
 * → 5. 주문 항목 재검증 (강좌가 open이고 미수강, 아니면 409 CART_CHANGED { unavailableCourseIds })
 * → 6. 내 users 행 잠금 + 포인트·쿠폰 사전 확인 (409 INSUFFICIENT_POINT / 409 COUPON_NOT_APPLICABLE { reason })
 * → 7. 가상 PG 서버끼리 호출 (amount = orders.Aprice). 응답의 merchantOrderId·amount가 주문과 다르면 승인 처리 안 함
 *      거절 402 PAYMENT_DECLINED { orderId, reason } / 응답 없음·시간 초과·그 밖의 오류 502 PG_UNAVAILABLE { orderId }
 * → 8. 승인이면 같은 트랜잭션에서 orders paid + 수강 등록 + 쿠폰 사용 + 포인트 차감 + 장바구니 삭제 → 커밋
 * → 9. 200 PAYMENT_COMPLETED { orderId, paymentKey, amount, pay, status, courseIds, approvedAt }.
 *
 * - 금액은 서버 값만: 요청에 amount가 와도 읽지 않는다 (요청 클래스에 필드가 없음). 회원도 토큰 sub로만 정한다.
 * - 포인트 사용액: POST /mypage/buy가 저장한 orders.use_point (Kim 2026-10-19). 요청 값은 읽지 않는다.
 * - 거절·502는 아무것도 쓰지 않으므로 주문은 pending으로 남아 재시도할 수 있다. 가상 PG는 승인된 주문에 멱등이라
 *   시간 초과 뒤 재요청해도 이중 승인이 생기지 않는다.
 * - 승인번호(paymentKey)는 DB에 저장하지 않는다 (CSV 원장 전용, Kim 결정 a안). 결제 정보 원문도 저장·로그하지 않는다.
 * - users 행을 잠그는 이유: 같은 회원의 다른 주문이 동시에 승인될 때 같은 쿠폰·포인트를 두 번 쓰지 못하게 한다.
 * - Aprice가 0인 pending 주문(정상이면 POST /mypage/buy에서 바로 paid)은 가상 PG를 부르지 않고 마무리한다 (paymentKey null).
 * 의도된 취약점 없음 (명세의 가격 조작·IDOR·포인트 경합은 나중에 따로 켠다).
 */
@RestController
public class payment {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");

    private final JdbcTemplate jdbc;
    private final JwtUtil jwtUtil;
    private final TransactionTemplate tx;
    private final VpgClient vpg;

    public payment(JdbcTemplate jdbc, JwtUtil jwtUtil, TransactionTemplate tx, VpgClient vpg) {
        this.jdbc = jdbc;
        this.jwtUtil = jwtUtil;
        this.tx = tx;
        this.vpg = vpg;
    }

    @PostMapping("/payment")
    public ResponseEntity<Map<String, Object>> pay(@RequestBody(required = false) PayRequest req,
                                                   HttpServletRequest request) {
        // 1. JWT 검증 (입력 검증보다 먼저)
        long userId = jwtUtil.getUid(request);
        PayRequest r = req == null ? new PayRequest() : req;

        // 2. orderId
        Long orderId = positiveId(r.orderId);
        if (orderId == null) {
            List<Map<String, String>> errors = new ArrayList<>();
            errors.add(error("orderId", "양의 정수여야 합니다."));
            return invalidInput(errors);
        }

        // 3~8. 주문 잠금부터 승인 반영까지 한 트랜잭션
        return tx.execute(status -> process(userId, orderId, r));
    }

    private ResponseEntity<Map<String, Object>> process(long userId, long orderId, PayRequest r) {
        // 3. 내 주문만, 잠금
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT oid, cid, discount, use_point, Aprice, pay, status FROM orders WHERE oid = ? AND user_id = ? FOR UPDATE",
                orderId, userId);
        if (rows.isEmpty()) {
            return ResponseEntity.status(404).body(body("ORDER_NOT_FOUND", "주문을 찾을 수 없습니다.", null));
        }
        Map<String, Object> order = rows.get(0);
        if (!"pending".equals(order.get("STATUS"))) {
            return ResponseEntity.status(409).body(body("ALREADY_PAID", "이미 결제가 완료된 주문입니다.", null));
        }
        String pay = (String) order.get("PAY");
        long aprice = num(order.get("APRICE"));
        Long cid = order.get("CID") == null ? null : num(order.get("CID"));

        // 4. 결제 정보 (orders.pay에 맞는 것만)
        List<Map<String, String>> errors = new ArrayList<>();
        Map<String, Object> info = paymentInfo(pay, r, errors);
        if (!errors.isEmpty()) return invalidInput(errors);

        // 5. 주문 항목 재검증. 강의가 강제 삭제돼 Lid가 NULL인 항목도 등록할 수 없으니 막는다
        List<Map<String, Object>> items = jdbc.queryForList(
                "SELECT oi.Lid AS course_id, oi.Lname AS lname, oi.price AS price,"
                        + "       CASE WHEN l.status = 'open'"
                        + "             AND NOT EXISTS (SELECT 1 FROM enrollments e WHERE e.user_id = ? AND e.Lid = oi.Lid)"
                        + "            THEN 1 ELSE 0 END AS available"
                        + "  FROM order_items oi LEFT JOIN lectures l ON l.Lid = oi.Lid"
                        + " WHERE oi.oid = ?"
                        + " ORDER BY oi.oiid", userId, orderId);
        List<Long> courseIds = new ArrayList<>();
        List<Long> unavailable = new ArrayList<>();
        List<String> names = new ArrayList<>();
        long itemsTotal = 0;
        boolean changed = items.isEmpty();
        for (Map<String, Object> it : items) {
            Long lid = it.get("COURSE_ID") == null ? null : num(it.get("COURSE_ID"));
            if (num(it.get("AVAILABLE")) != 1) {
                changed = true;
                if (lid != null) unavailable.add(lid);
            }
            if (lid != null) courseIds.add(lid);
            names.add((String) it.get("LNAME"));
            itemsTotal += num(it.get("PRICE"));
        }
        if (changed) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("unavailableCourseIds", unavailable);
            return ResponseEntity.status(409).body(body("CART_CHANGED", "주문할 수 없는 강의가 포함되어 있습니다.", result));
        }

        // 6. 포인트·쿠폰 사전 확인 (승인 뒤에 모자라면 돈만 나간 주문이 되므로 PG 호출 전에 본다)
        long usePoint = num(order.get("USE_POINT"));
        jdbc.queryForList("SELECT user_id FROM users WHERE user_id = ? FOR UPDATE", Long.class, userId);
        long point = buy.pointBalance(jdbc, userId);
        if (point < usePoint) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("orderId", orderId);
            result.put("balance", point);
            result.put("usePoint", usePoint);
            return ResponseEntity.status(409).body(body("INSUFFICIENT_POINT", "포인트 잔액이 부족합니다.", result));
        }
        if (cid != null) {
            List<Long> used = jdbc.queryForList(
                    "SELECT used FROM user_coupons WHERE user_id = ? AND cid = ? FOR UPDATE", Long.class, userId, cid);
            String reason = used.isEmpty() ? "COUPON_NOT_FOUND" : used.get(0) != 0 ? "ALREADY_USED" : null;
            if (reason != null) {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("reason", reason);
                return ResponseEntity.status(409).body(body("COUPON_NOT_APPLICABLE", "이 주문에 사용할 수 없는 쿠폰입니다.", result));
            }
        }

        // 7. 가상 PG 승인 (금액은 orders.Aprice만)
        String paymentKey = null;
        String approvedAt = ZonedDateTime.now(KST).format(ISO);
        if (aprice > 0) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("merchantOrderId", String.valueOf(orderId));
            payload.put("amount", aprice);
            payload.put("orderName", orderName(names));
            payload.put("method", pay);
            payload.put("CARD".equals(pay) ? "card" : "transfer", info);
            VpgClient.Reply reply = vpg.confirm(payload);
            Map<?, ?> res = reply == null || reply.status != 200 || reply.body == null
                    || !(reply.body.get("result") instanceof Map) ? null : (Map<?, ?>) reply.body.get("result");
            boolean sameOrder = res != null && String.valueOf(orderId).equals(res.get("merchantOrderId"))
                    && res.get("amount") instanceof Number && ((Number) res.get("amount")).longValue() == aprice;
            if (!sameOrder) return pgUnavailable(orderId);
            if ("DECLINED".equals(res.get("status"))) {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("orderId", orderId);
                result.put("reason", res.get("reasonCode"));
                return ResponseEntity.status(402)
                        .body(body("PAYMENT_DECLINED", "결제가 승인되지 않았습니다. 결제 정보를 확인해주세요.", result));
            }
            if (!"APPROVED".equals(res.get("status"))) return pgUnavailable(orderId);
            paymentKey = (String) res.get("approvalId");
            if (res.get("approvedAt") != null) approvedAt = (String) res.get("approvedAt");
        }

        // 8. 승인 반영. 하나라도 어긋나면 예외로 전체 롤백 (주문은 pending, 가상 PG 재요청은 멱등)
        expectOne(jdbc.update("UPDATE orders SET status = 'paid', paid_at = LOCALTIMESTAMP WHERE oid = ? AND status = 'pending'", orderId));
        for (Long lid : courseIds) {
            jdbc.update("INSERT INTO enrollments (user_id, Lid, done, enrolled_at)"
                    + " SELECT ?, ?, 0, LOCALTIMESTAMP FROM dual"
                    + " WHERE NOT EXISTS (SELECT 1 FROM enrollments WHERE user_id = ? AND Lid = ?)", userId, lid, userId, lid);
        }
        if (cid != null) {
            expectOne(jdbc.update("UPDATE user_coupons SET used = 1, used_at = LOCALTIMESTAMP WHERE user_id = ? AND cid = ? AND used = 0",
                    userId, cid));
        }
        if (buy.POINTS_ENABLED && usePoint > 0) {
            expectOne(jdbc.update("UPDATE users SET point = point - ? WHERE user_id = ? AND point >= ?", usePoint, userId, usePoint));
        }
        if (!courseIds.isEmpty()) {
            List<Object> args = new ArrayList<>();
            args.add(userId);
            args.addAll(courseIds);
            jdbc.update("DELETE FROM cart WHERE user_id = ? AND Lid IN ("
                    + String.join(", ", Collections.nCopies(courseIds.size(), "?")) + ")", args.toArray());
        }

        // 9. 응답
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("orderId", orderId);
        result.put("paymentKey", paymentKey);
        result.put("amount", aprice);
        result.put("pay", pay);
        result.put("status", "paid");
        result.put("courseIds", courseIds);
        result.put("approvedAt", approvedAt);
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(body("PAYMENT_COMPLETED", "결제가 완료되었습니다.", result));
    }

    /**
     * orders.pay에 맞는 결제 정보를 가상 PG로 보낼 모양으로 정리한다. 카드 번호는 공백·하이픈을 뺀다.
     * Luhn·유효기간 만료는 여기서 막지 않는다: 가상 PG가 INVALID_CARD / EXPIRED_CARD로 거절하고 402가 된다.
     */
    static Map<String, Object> paymentInfo(String pay, PayRequest r, List<Map<String, String>> errors) {
        Map<String, Object> out = new LinkedHashMap<>();
        if ("CARD".equals(pay)) {
            if (r.transfer != null) errors.add(error("transfer", "카드 결제 주문에는 계좌 정보를 보낼 수 없습니다."));
            if (!(r.card instanceof Map)) {
                errors.add(error("card", "카드 결제 정보가 필요합니다."));
                return out;
            }
            Map<?, ?> c = (Map<?, ?>) r.card;
            Object number = c.get("number");
            String digits = number instanceof String ? ((String) number).replaceAll("[\\s-]", "") : "";
            if (!digits.matches("[0-9]{14,16}")) errors.add(error("card.number", "숫자 14~16자리여야 합니다."));
            if (!matches(c.get("expiry"), "(0[1-9]|1[0-2])/[0-9]{2}")) errors.add(error("card.expiry", "MM/YY 형식이어야 합니다."));
            if (!matches(c.get("cvc"), "[0-9]{3}")) errors.add(error("card.cvc", "숫자 3자리여야 합니다."));
            Object inst = c.get("installment") == null ? 0 : c.get("installment");
            boolean instOk = inst instanceof Integer && ((Integer) inst == 0 || ((Integer) inst >= 2 && (Integer) inst <= 12));
            if (!instOk) errors.add(error("card.installment", "0(일시불) 또는 2~12여야 합니다."));
            out.put("number", digits);
            out.put("expiry", c.get("expiry"));
            out.put("cvc", c.get("cvc"));
            out.put("installment", inst);
        } else if ("TRANSFER".equals(pay)) {
            if (r.card != null) errors.add(error("card", "계좌이체 주문에는 카드 정보를 보낼 수 없습니다."));
            if (!(r.transfer instanceof Map)) {
                errors.add(error("transfer", "계좌이체 정보가 필요합니다."));
                return out;
            }
            Map<?, ?> t = (Map<?, ?>) r.transfer;
            if (!matches(t.get("bankCode"), "[0-9]{3}")) errors.add(error("transfer.bankCode", "숫자 3자리여야 합니다."));
            if (!matches(t.get("accountNumber"), "[0-9]{10,14}")) errors.add(error("transfer.accountNumber", "숫자 10~14자리여야 합니다."));
            Object holder = t.get("holderName");
            if (!(holder instanceof String) || isBlank((String) holder) || ((String) holder).length() > 20) {
                errors.add(error("transfer.holderName", "1~20자여야 합니다."));
            }
            out.put("bankCode", t.get("bankCode"));
            out.put("accountNumber", t.get("accountNumber"));
            out.put("holderName", holder);
        } else {
            errors.add(error("orderId", "결제 수단이 정해지지 않은 주문입니다."));
        }
        return out;
    }

    /** 1개면 강좌명, 여러 개면 "첫 강좌명 외 N개" (가상 PG orderName 1~100자) */
    static String orderName(List<String> names) {
        String n = names.size() == 1 ? names.get(0) : names.get(0) + " 외 " + (names.size() - 1) + "개";
        return n.length() > 100 ? n.substring(0, 100) : n;
    }

    static Long positiveId(Object v) {
        if (!(v instanceof Integer) && !(v instanceof Long)) return null;
        long n = ((Number) v).longValue();
        return n > 0 ? n : null;
    }

    private static boolean matches(Object v, String regex) {
        return v instanceof String && ((String) v).matches(regex);
    }

    private static void expectOne(int updated) {
        if (updated != 1) throw new IllegalStateException("결제 승인 반영 중 상태가 바뀌었습니다.");
    }

    private static ResponseEntity<Map<String, Object>> pgUnavailable(long orderId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("orderId", orderId);
        return ResponseEntity.status(502)
                .body(body("PG_UNAVAILABLE", "결제 확인이 지연되고 있습니다. 결제 내역에서 결과를 확인해주세요.", result));
    }

    /**
     * 요청 본문 { orderId, card?, transfer? }. 형식 오류를 field 단위로 알려 주려고 Object로 받는다.
     * 금액·회원·할인 필드는 일부러 두지 않는다 (보내도 무시).
     */
    public static class PayRequest {
        public Object orderId;
        public Object card;
        public Object transfer;
    }
}
