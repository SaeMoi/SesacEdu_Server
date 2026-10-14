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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.example.sesacedu.global.ApiResponse.*;

/**
 * GET /mypage/buy/history?status=&page=&size= (Notion API 명세서: 결제한 이력들 확인).
 *
 * 처리 순서: 1. Authorization의 JWT 검증 (없음·위조 401 UNAUTHORIZED, 만료 401 TOKEN_EXPIRED)
 * → 2. 쿼리 검증 (status ALL|PAID|REFUNDED, page 0 이상, size 1~50, 아니면 400 INVALID_INPUT)
 * → 3. 내 주문 건수 + 페이지 목록 (created DESC, oid DESC) → 4. 페이지 주문들의 order_items로 orderName·courseCount
 * → 5. 200 ORDER_HISTORY_FOUND { orders, page }.
 * 대상은 토큰 sub로만 정한다 (orders.user_id = 나, IDOR 없음). 개인정보라 Cache-Control: no-store.
 *
 * 필터 → orders.status: ALL = paid·refunded, PAID = paid, REFUNDED = refunded. pending·cancelled는 내역에 보이지 않는다.
 * 상태 값은 고정 문자열 IN 목록으로만 SQL에 들어간다 (요청 값을 이어 붙이지 않는다).
 * hasReceipt는 성능상 CSV를 보지 않고 Aprice > 0으로 판단한다 (명세). 실제 승인행 유무는 결제 상세가 확인한다.
 *
 * 명세와 다르게 정한 것 (db/schema.sql 기준):
 * - 회원 컬럼은 uid가 아니라 user_id.
 * - orderName은 lectures.name 조인이 아니라 order_items.Lname(구매 시점 스냅샷)으로 만든다. 강의가 강제 삭제돼
 *   Lid가 NULL이 된 항목도 이름이 남고, 주문 뒤 강의명이 바뀌어도 주문 당시 이름이 보인다.
 * - 항목 순서는 oiid(주문에 넣은 순서)라 orderName이 POST /mypage/buy 응답의 orderName과 같다 (명세는 Lid 순).
 * 의도된 취약점 없음. IDOR 포인트는 결제 상세(buy_receipt)에 있다.
 */
@RestController
public class buy_history {

    private final JdbcTemplate jdbc;
    private final JwtUtil jwtUtil;

    public buy_history(JdbcTemplate jdbc, JwtUtil jwtUtil) {
        this.jdbc = jdbc;
        this.jwtUtil = jwtUtil;
    }

    @GetMapping("/mypage/buy/history")
    public ResponseEntity<Map<String, Object>> history(@RequestParam(required = false) String status,
                                                       @RequestParam(required = false) String page,
                                                       @RequestParam(required = false) String size,
                                                       HttpServletRequest request) {
        // 1. JWT 검증 (쿼리 검증보다 먼저)
        long userId = jwtUtil.getUid(request);

        // 2. 쿼리 검증. 빈 값은 기본값
        status = isBlank(status) ? "ALL" : status.trim();
        List<Map<String, String>> errors = new ArrayList<>();
        String statusFilter;
        switch (status) {
            case "ALL": statusFilter = "'paid', 'refunded'"; break;
            case "PAID": statusFilter = "'paid'"; break;
            case "REFUNDED": statusFilter = "'refunded'"; break;
            default:
                statusFilter = null;
                errors.add(error("status", "ALL, PAID, REFUNDED 중 하나여야 합니다."));
        }
        Integer pageNo = parseInt(page, 0);
        if (pageNo == null || pageNo < 0) errors.add(error("page", "0 이상의 정수여야 합니다."));
        Integer pageSize = parseInt(size, 10);
        if (pageSize == null || pageSize < 1 || pageSize > 50) errors.add(error("size", "1~50 사이의 정수여야 합니다."));
        if (!errors.isEmpty()) return invalidInput(errors);

        // 3. 건수 + 목록. 바인드 변수만, IN 목록은 위 switch의 고정 문자열
        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM orders WHERE user_id = ? AND status IN (" + statusFilter + ")", Long.class, userId);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT oid AS order_id, Aprice AS final_amount, status AS status, created AS ordered_at"
                        + "  FROM orders"
                        + " WHERE user_id = ? AND status IN (" + statusFilter + ")"
                        + " ORDER BY created DESC, oid DESC"
                        + " OFFSET ? ROWS FETCH NEXT ? ROWS ONLY",
                userId, (long) pageNo * pageSize, pageSize);

        // 4. 이 페이지 주문들의 항목 이름 (oid별, 주문에 넣은 순서)
        Map<Long, List<String>> names = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) names.put(num(r.get("ORDER_ID")), new ArrayList<>());
        if (!names.isEmpty()) {
            for (Map<String, Object> it : jdbc.queryForList(
                    "SELECT oid AS order_id, Lname AS title FROM order_items WHERE oid IN ("
                            + String.join(", ", Collections.nCopies(names.size(), "?")) + ")"
                            + " ORDER BY oid, oiid", names.keySet().toArray())) {
                names.get(num(it.get("ORDER_ID"))).add((String) it.get("TITLE"));
            }
        }

        List<Map<String, Object>> orders = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            long orderId = num(r.get("ORDER_ID"));
            long finalAmount = num(r.get("FINAL_AMOUNT"));
            String st = (String) r.get("STATUS");
            List<String> titles = names.get(orderId);
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("orderId", orderId);
            o.put("orderName", buy.orderName(titles));
            o.put("courseCount", titles.size());
            o.put("finalAmount", finalAmount);
            o.put("status", st.toUpperCase());
            o.put("statusLabel", statusLabel(st, finalAmount));
            o.put("hasReceipt", finalAmount > 0);
            o.put("orderedAt", iso(r.get("ORDERED_AT")));
            orders.add(o);
        }

        // 5. 응답
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("orders", orders);
        result.put("page", pageInfo(pageNo, pageSize, total == null ? 0 : total));
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(body("ORDER_HISTORY_FOUND", "결제 내역을 조회했습니다.", result));
    }

    /**
     * 결제 완료 / 등록 완료(paid + 0원) / 환불 완료. 결제 상세에서만 보이는 pending·cancelled는
     * 결제 대기 / 주문 취소 (명세에 없는 값이라 상세 주석 참고)
     */
    static String statusLabel(String status, long finalAmount) {
        switch (status) {
            case "paid": return finalAmount == 0 ? "등록 완료" : "결제 완료";
            case "refunded": return "환불 완료";
            case "pending": return "결제 대기";
            case "cancelled": return "주문 취소";
            default: return status;
        }
    }
}
