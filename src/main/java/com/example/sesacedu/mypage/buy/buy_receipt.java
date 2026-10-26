package com.example.sesacedu.mypage.buy;

import com.example.sesacedu.global.JwtUtil;
import com.example.sesacedu.virtualpg.VpgLedger;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.example.sesacedu.global.ApiResponse.*;

/**
 * GET /mypage/buy/history/{orderId} (Notion API 명세서: 결제 상세 · 영수증).
 *
 * 처리 순서: 1. Authorization의 JWT 검증 (없음·위조 401 UNAUTHORIZED, 만료 401 TOKEN_EXPIRED)
 * → 2. 내 주문 조회 (oid = orderId AND user_id = 나. 없음·남의 주문·양의 정수가 아닌 orderId 모두 404 ORDER_NOT_FOUND)
 * → 3. order_items로 items·orderName·productAmount
 * → 4. 승인 정보: 가상 PG CSV 원장의 메모리 인덱스(VpgLedger)에서 merchant_order_id = orderId인 승인 행. 없으면 payment null
 * → 5. 200 ORDER_DETAIL_FOUND { order, payment, hasReceipt }. 개인정보라 Cache-Control: no-store.
 *
 * 승인번호·승인 시각·카드 뒤 4자리는 CSV 원장에만 있다 (Kim 결정 a안, orders에 승인번호 컬럼 없음).
 * 카드 번호 전체·CVC·유효기간·계좌 전체는 원장에도 없어 응답에 들어갈 수 없다.
 *
 * 명세와 다르게 정한 것 (db/schema.sql 기준):
 * - 회원 컬럼은 uid가 아니라 user_id.
 * - usePoint: orders.use_point (Kim 2026-10-19).
 * - items[].title은 lectures.name 조인이 아니라 order_items.Lname(구매 시점 스냅샷). 강의가 강제 삭제된 항목은
 *   courseId null로 남는다 (명세 쿼리의 JOIN은 그 항목을 빠뜨려 productAmount가 어긋난다). 순서는 oiid(주문에 넣은 순서).
 * - 명세의 status는 PAID | REFUNDED뿐이지만 pending(PG 시간 초과 뒤 확인 등)·cancelled 주문도 내 주문이면 보여 준다
 *   (status PENDING / CANCELLED, statusLabel 결제 대기 / 주문 취소). 목록에는 여전히 paid·refunded만 나온다.
 * - payment는 원장의 「마지막 행」이 아니라 승인(APPROVED) 행이다. 인덱스가 승인 주문만 들고 있고(CANCELED가 오면 빠짐),
 *   거절(DECLINED) 행은 영수증이 아니다. 기동 뒤 다시 읽은 행도 같은 값이 나온다 (cardFirst4는 쓰지 않는다).
 *
 * 의도된 취약점 후보 (지금은 꺼져 있음): 명세 「주문 열거 / IDOR」. 아래 ORDER_SQL에서 「AND user_id = ?」를 빼고
 * 바인드 인자에서 userId를 빼면 순차 oid로 남의 주문·영수증(승인번호·카드 뒤 4자리·금액)이 보인다.
 */
@RestController
public class buy_receipt {

    /** 내 주문 한 건. IDOR을 켤 때는 여기의 user_id 조건을 뺀다 */
    static final String ORDER_SQL =
            "SELECT oid AS order_id, discount AS discount, use_point AS use_point, Aprice AS final_amount, pay AS pay, status AS status,"
                    + "       created AS ordered_at"
                    + "  FROM orders"
                    // [의도된 취약점 — OWASP A01:2021 Broken Access Control / IDOR] user_id 소유 확인을 뺐다.
                    //   순차적인 oid 만 넘기면 남의 주문·영수증(승인번호·카드 뒤 4자리·금액)이 그대로 열린다.
                    + " WHERE oid = ?";

    private final JdbcTemplate jdbc;
    private final JwtUtil jwtUtil;
    private final VpgLedger ledger;

    public buy_receipt(JdbcTemplate jdbc, JwtUtil jwtUtil, VpgLedger ledger) {
        this.jdbc = jdbc;
        this.jwtUtil = jwtUtil;
        this.ledger = ledger;
    }

    @GetMapping("/mypage/buy/history/{orderId}")
    public ResponseEntity<Map<String, Object>> detail(@PathVariable String orderId, HttpServletRequest request) {
        // 1. JWT 검증
        long userId = jwtUtil.getUid(request);

        // 2. 내 주문만. 숫자가 아닌 orderId도 「없음」으로 처리한다 (명세에 400 없음)
        Long oid = buy.parsePositive(orderId);
        // userId 는 여전히 토큰에서 읽어 로그인은 요구하지만, 조회에는 쓰지 않는다 (위 ORDER_SQL의 의도된 IDOR).
        List<Map<String, Object>> rows = oid == null ? new ArrayList<>() : jdbc.queryForList(ORDER_SQL, oid);
        if (rows.isEmpty()) {
            return ResponseEntity.status(404).body(body("ORDER_NOT_FOUND", "주문을 찾을 수 없습니다.", null));
        }
        Map<String, Object> o = rows.get(0);
        long discount = num(o.get("DISCOUNT"));
        long finalAmount = num(o.get("FINAL_AMOUNT"));
        String status = (String) o.get("STATUS");

        // 3. 주문 항목 (구매 시점 이름·가격 스냅샷)
        List<Map<String, Object>> items = new ArrayList<>();
        List<String> titles = new ArrayList<>();
        long productAmount = 0;
        for (Map<String, Object> r : jdbc.queryForList(
                "SELECT Lid AS course_id, Lname AS title, price AS price FROM order_items WHERE oid = ? ORDER BY oiid", oid)) {
            long price = num(r.get("PRICE"));
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("courseId", r.get("COURSE_ID") == null ? null : num(r.get("COURSE_ID")));
            item.put("title", r.get("TITLE"));
            item.put("price", price);
            items.add(item);
            titles.add((String) r.get("TITLE"));
            productAmount += price;
        }

        Map<String, Object> order = new LinkedHashMap<>();
        order.put("orderId", oid);
        order.put("status", status.toUpperCase());
        order.put("statusLabel", buy_history.statusLabel(status, finalAmount));
        order.put("orderName", buy.orderName(titles));
        order.put("items", items);
        order.put("productAmount", productAmount);
        order.put("couponDiscountAmount", discount);
        order.put("usePoint", num(o.get("USE_POINT")));
        order.put("finalAmount", finalAmount);
        order.put("pay", o.get("PAY"));
        order.put("orderedAt", iso(o.get("ORDERED_AT")));

        // 4. 승인 정보 (CSV 원장 인덱스)
        Map<String, Object> payment = payment(ledger.approvedEntry(String.valueOf(oid)));

        // 5. 응답
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("order", order);
        result.put("payment", payment);
        result.put("hasReceipt", finalAmount > 0 && payment != null);
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(body("ORDER_DETAIL_FOUND", "주문 상세를 조회했습니다.", result));
    }

    /** 원장 행 → payment. 카드면 계좌 칸, 계좌이체면 카드 칸을 null로 */
    static Map<String, Object> payment(VpgLedger.Entry e) {
        if (e == null) return null;
        boolean card = "CARD".equals(e.method);
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("paymentKey", e.approvalId);
        p.put("status", e.status);
        p.put("method", e.method);
        p.put("cardBrand", card ? e.cardBrand : null);
        p.put("cardLast4", card ? e.cardLast4 : null);
        p.put("installment", card ? e.installment : null);
        p.put("bankCode", card ? null : e.bankCode);
        p.put("accountLast4", card ? null : e.accountLast4);
        p.put("amount", e.amount);
        p.put("approvedAt", e.approvedAt);
        return p;
    }
}
