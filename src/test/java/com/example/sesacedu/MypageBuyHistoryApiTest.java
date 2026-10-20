package com.example.sesacedu;

import com.example.sesacedu.global.ApiResponse;
import com.example.sesacedu.virtualpg.VpgLedger;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.Date;
import java.util.Objects;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * GET /mypage/buy/history (buy_history)와 GET /mypage/buy/history/{orderId} (buy_receipt)가
 * Notion 명세(결제한 이력들 확인, 결제 상세 · 영수증)대로 응답하는지 확인.
 * 주문은 DB에 직접 넣고, 승인 기록은 VpgLedger.append로 실행마다 새 임시 CSV 원장에 쓴다.
 *   gradlew test --tests com.example.sesacedu.MypageBuyHistoryApiTest
 */
@SuppressWarnings("null") // VS Code(JDT) null 분석이 Spring/MockMvc API에 내는 경고 제외. 실행에는 영향 없음
@SpringBootTest(properties = {
        "JWT_SECRET=" + MypageBuyHistoryApiTest.SECRET,
        "vpg.ledger-path=${java.io.tmpdir}/sesacedu-vpg-test/${random.uuid}/approvals.csv"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MypageBuyHistoryApiTest {

    static final String SECRET = "mypage-history-test-secret-at-least-32-bytes!!";

    static final Timestamp T_CARD = Timestamp.valueOf("2026-10-07 16:19:31");
    static final Timestamp T_FREE = Timestamp.valueOf("2026-09-25 10:02:47");
    static final Timestamp T_REFUND = Timestamp.valueOf("2026-10-08 09:00:00");
    static final Timestamp T_TRANSFER = Timestamp.valueOf("2026-10-01 12:00:00");

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired VpgLedger ledger;

    long me;
    long other;

    @BeforeEach
    void setUp() throws Exception {
        for (String t : new String[]{"order_items", "orders", "users"}) {
            jdbc.update("DELETE FROM " + t);
        }
        jdbc.update("INSERT INTO users (mail, pw, uname, nickname, auth) VALUES ('hist-me@example.test', 'x', '김내역', '내역', 1)");
        jdbc.update("INSERT INTO users (mail, pw, uname, nickname, auth) VALUES ('hist-other@example.test', 'x', '이타인', '타인', 1)");
        me = id("hist-me@example.test");
        other = id("hist-other@example.test");

        // 10231 카드 결제 (쿠폰 5000) / 10180 무료 / 10300 환불 / 10250 계좌이체 / 10400 pending / 10401 cancelled / 10500 남의 주문
        order(10231, me, 5000, 83000, "CARD", "paid", T_CARD);
        item(10231, 12L, "웹 보안 기초", 55000);
        item(10231, 7L, "네트워크 기초", 33000);
        order(10180, me, 5000, 0, null, "paid", T_FREE);
        item(10180, 7L, "네트워크 기초", 5000);
        order(10300, me, 0, 30000, "CARD", "refunded", T_REFUND);
        item(10300, null, "삭제된 강의", 30000);     // 강의 강제 삭제 → Lid NULL, Lname만 남음
        order(10250, me, 0, 49000, "TRANSFER", "paid", T_TRANSFER);
        item(10250, 20L, "보안 심화 실습", 49000);
        order(10400, me, 0, 25000, "CARD", "pending", Timestamp.valueOf("2026-10-09 09:00:00"));
        item(10400, 16L, "새 강의", 25000);
        order(10401, me, 0, 25000, "CARD", "cancelled", Timestamp.valueOf("2026-10-09 10:00:00"));
        item(10401, 16L, "새 강의", 25000);
        order(10500, other, 0, 39000, "CARD", "paid", Timestamp.valueOf("2026-10-10 10:00:00"));
        item(10500, 7L, "리눅스 입문", 39000);

        if (ledger.approvedEntry("10231") == null) {
            ledger.append(card("10231", 83000, "1111"), "20261007");
            ledger.append(card("10300", 30000, "2222"), "20261008");
            ledger.append(card("10500", 39000, "4444"), "20261010");
            VpgLedger.Entry t = new VpgLedger.Entry();
            t.merchantOrderId = "10250";
            t.status = "APPROVED";
            t.amount = 49000;
            t.method = "TRANSFER";
            t.bankCode = "004";
            t.accountLast4 = "5678";
            t.requestedAt = "2026-10-01T12:00:05+09:00";
            t.approvedAt = "2026-10-01T12:00:06+09:00";
            ledger.append(t, "20261001");
            // 거절 행은 영수증이 아니다 (pending 주문 10400은 payment null)
            VpgLedger.Entry d = card("10400", 25000, "0002");
            d.status = "DECLINED";
            d.reasonCode = "INSUFFICIENT_BALANCE";
            d.approvedAt = null;
            ledger.append(d, "20261009");
        }
    }

    // ------------------------------------------------------------ helpers

    long id(String mail) {
        return Objects.requireNonNull(jdbc.queryForObject("SELECT user_id FROM users WHERE mail = ?", Long.class, mail));
    }

    void order(long oid, long uid, long discount, long aprice, String pay, String status, Timestamp created) {
        jdbc.update("INSERT INTO orders (oid, ono, user_id, discount, Aprice, pay, status, created) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                oid, "ORD-x-" + oid, uid, discount, aprice, pay, status, created);
    }

    void item(long oid, Long lid, String name, long price) {
        jdbc.update("INSERT INTO order_items (oid, Lid, Lname, price) VALUES (?, ?, ?, ?)", oid, lid, name, price);
    }

    static VpgLedger.Entry card(String oid, long amount, String last4) {
        VpgLedger.Entry e = new VpgLedger.Entry();
        e.merchantOrderId = oid;
        e.status = "APPROVED";
        e.amount = amount;
        e.method = "CARD";
        e.cardBrand = "VISA";
        e.cardFirst4 = "4000";
        e.cardLast4 = last4;
        e.installment = 0;
        e.requestedAt = "2026-10-07T16:19:40+09:00";
        e.approvedAt = "2026-10-07T16:19:41+09:00";
        return e;
    }

    static String bearer(long sub) {
        return "Bearer " + Jwts.builder().subject(String.valueOf(sub)).claim("role", "STUDENT").claim("reissue_num", 2)
                .expiration(new Date(System.currentTimeMillis() + 3600_000))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)), Jwts.SIG.HS256).compact();
    }

    ResultActions list(String query) throws Exception {
        return mvc.perform(get("/mypage/buy/history" + query).header("Authorization", bearer(me)));
    }

    ResultActions detail(String orderId) throws Exception {
        return mvc.perform(get("/mypage/buy/history/" + orderId).header("Authorization", bearer(me)));
    }

    // ------------------------------------------------------------ GET /mypage/buy/history

    @Test
    @DisplayName("목록: 내 paid·refunded 주문만 최신순, pending·cancelled·남의 주문은 빠진다")
    void listAll() throws Exception {
        list("")
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("ORDER_HISTORY_FOUND"))
                .andExpect(jsonPath("$.message").value("결제 내역을 조회했습니다."))
                .andExpect(jsonPath("$.result.orders[*].orderId", contains(10300, 10231, 10250, 10180)))
                .andExpect(jsonPath("$.result.orders[1].orderName").value("웹 보안 기초 외 1개"))
                .andExpect(jsonPath("$.result.orders[1].courseCount").value(2))
                .andExpect(jsonPath("$.result.orders[1].finalAmount").value(83000))
                .andExpect(jsonPath("$.result.orders[1].status").value("PAID"))
                .andExpect(jsonPath("$.result.orders[1].statusLabel").value("결제 완료"))
                .andExpect(jsonPath("$.result.orders[1].hasReceipt").value(true))
                .andExpect(jsonPath("$.result.orders[1].orderedAt").value(ApiResponse.iso(T_CARD)))
                .andExpect(jsonPath("$.result.orders[0].orderName").value("삭제된 강의"))
                .andExpect(jsonPath("$.result.orders[0].status").value("REFUNDED"))
                .andExpect(jsonPath("$.result.orders[0].statusLabel").value("환불 완료"))
                .andExpect(jsonPath("$.result.orders[3].finalAmount").value(0))
                .andExpect(jsonPath("$.result.orders[3].statusLabel").value("등록 완료"))
                .andExpect(jsonPath("$.result.orders[3].hasReceipt").value(false))
                .andExpect(jsonPath("$.result.page.number").value(0))
                .andExpect(jsonPath("$.result.page.size").value(10))
                .andExpect(jsonPath("$.result.page.totalElements").value(4))
                .andExpect(jsonPath("$.result.page.totalPages").value(1))
                .andExpect(jsonPath("$.result.page.hasNext").value(false));
    }

    @Test
    @DisplayName("목록: status 필터와 페이지")
    void listFilterAndPage() throws Exception {
        list("?status=PAID")
                .andExpect(jsonPath("$.result.orders[*].orderId", contains(10231, 10250, 10180)))
                .andExpect(jsonPath("$.result.page.totalElements").value(3));
        list("?status=REFUNDED")
                .andExpect(jsonPath("$.result.orders[*].orderId", contains(10300)));
        list("?status=PAID&page=1&size=2")
                .andExpect(jsonPath("$.result.orders[*].orderId", contains(10180)))
                .andExpect(jsonPath("$.result.page.totalPages").value(2))
                .andExpect(jsonPath("$.result.page.hasNext").value(false));
        list("?page=5")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.orders", hasSize(0)));
    }

    @Test
    @DisplayName("목록: 내역이 없으면 200 빈 목록")
    void listEmpty() throws Exception {
        jdbc.update("DELETE FROM orders WHERE user_id = ? AND status = 'refunded'", me);
        list("?status=REFUNDED")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.orders", hasSize(0)))
                .andExpect(jsonPath("$.result.page.totalElements").value(0))
                .andExpect(jsonPath("$.result.page.totalPages").value(0));
    }

    @Test
    @DisplayName("목록: 잘못된 status·page·size는 400 INVALID_INPUT")
    void listInvalid() throws Exception {
        list("?status=PENDING")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.result.errors[0].field").value("status"))
                .andExpect(jsonPath("$.result.errors[0].reason").value("ALL, PAID, REFUNDED 중 하나여야 합니다."));
        list("?status=paid'--")
                .andExpect(status().isBadRequest());
        list("?page=-1&size=51")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.result.errors[*].field", contains("page", "size")));
        list("?size=x")
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("401: 토큰 없음·위조")
    void unauthorized() throws Exception {
        mvc.perform(get("/mypage/buy/history"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mvc.perform(get("/mypage/buy/history/10231"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mvc.perform(get("/mypage/buy/history/10231").header("Authorization", "Bearer abc.def.ghi"))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------ GET /mypage/buy/history/{orderId}

    @Test
    @DisplayName("상세: 카드 결제 영수증 (CSV 원장 승인 정보, 카드는 뒤 4자리만)")
    void detailCard() throws Exception {
        detail("10231")
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("ORDER_DETAIL_FOUND"))
                .andExpect(jsonPath("$.message").value("주문 상세를 조회했습니다."))
                .andExpect(jsonPath("$.result.order.orderId").value(10231))
                .andExpect(jsonPath("$.result.order.status").value("PAID"))
                .andExpect(jsonPath("$.result.order.statusLabel").value("결제 완료"))
                .andExpect(jsonPath("$.result.order.orderName").value("웹 보안 기초 외 1개"))
                .andExpect(jsonPath("$.result.order.items[*].courseId", contains(12, 7)))
                .andExpect(jsonPath("$.result.order.items[*].title", contains("웹 보안 기초", "네트워크 기초")))
                .andExpect(jsonPath("$.result.order.items[*].price", contains(55000, 33000)))
                .andExpect(jsonPath("$.result.order.productAmount").value(88000))
                .andExpect(jsonPath("$.result.order.couponDiscountAmount").value(5000))
                .andExpect(jsonPath("$.result.order.usePoint").value(0))
                .andExpect(jsonPath("$.result.order.finalAmount").value(83000))
                .andExpect(jsonPath("$.result.order.pay").value("CARD"))
                .andExpect(jsonPath("$.result.order.orderedAt").value(ApiResponse.iso(T_CARD)))
                .andExpect(jsonPath("$.result.payment.paymentKey").value(startsWith("VPG-20261007-")))
                .andExpect(jsonPath("$.result.payment.status").value("APPROVED"))
                .andExpect(jsonPath("$.result.payment.method").value("CARD"))
                .andExpect(jsonPath("$.result.payment.cardBrand").value("VISA"))
                .andExpect(jsonPath("$.result.payment.cardLast4").value("1111"))
                .andExpect(jsonPath("$.result.payment.installment").value(0))
                .andExpect(jsonPath("$.result.payment.bankCode").value(nullValue()))
                .andExpect(jsonPath("$.result.payment.accountLast4").value(nullValue()))
                .andExpect(jsonPath("$.result.payment.amount").value(83000))
                .andExpect(jsonPath("$.result.payment.approvedAt").value("2026-10-07T16:19:41+09:00"))
                .andExpect(jsonPath("$.result.payment.cardMasked").doesNotExist())
                .andExpect(jsonPath("$.result.payment.cardNumber").doesNotExist())
                .andExpect(jsonPath("$.result.hasReceipt").value(true));
    }

    @Test
    @DisplayName("상세: usePoint는 orders.use_point 값 그대로")
    void detailUsePoint() throws Exception {
        jdbc.update("UPDATE orders SET use_point = 2000 WHERE oid = 10231");
        detail("10231")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.order.usePoint").value(2000))
                .andExpect(jsonPath("$.result.order.couponDiscountAmount").value(5000));
    }

    @Test
    @DisplayName("상세: 계좌이체 영수증은 은행 코드·계좌 뒤 4자리, 카드 칸은 null")
    void detailTransfer() throws Exception {
        detail("10250")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.order.pay").value("TRANSFER"))
                .andExpect(jsonPath("$.result.payment.method").value("TRANSFER"))
                .andExpect(jsonPath("$.result.payment.bankCode").value("004"))
                .andExpect(jsonPath("$.result.payment.accountLast4").value("5678"))
                .andExpect(jsonPath("$.result.payment.cardBrand").value(nullValue()))
                .andExpect(jsonPath("$.result.payment.cardLast4").value(nullValue()))
                .andExpect(jsonPath("$.result.payment.installment").value(nullValue()))
                .andExpect(jsonPath("$.result.payment.amount").value(49000))
                .andExpect(jsonPath("$.result.hasReceipt").value(true));
    }

    @Test
    @DisplayName("상세: 무료(0원) 주문은 payment null, hasReceipt false")
    void detailFree() throws Exception {
        detail("10180")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.order.statusLabel").value("등록 완료"))
                .andExpect(jsonPath("$.result.order.productAmount").value(5000))
                .andExpect(jsonPath("$.result.order.couponDiscountAmount").value(5000))
                .andExpect(jsonPath("$.result.order.usePoint").value(0))
                .andExpect(jsonPath("$.result.order.finalAmount").value(0))
                .andExpect(jsonPath("$.result.order.pay").value(nullValue()))
                .andExpect(jsonPath("$.result.payment").value(nullValue()))
                .andExpect(jsonPath("$.result.hasReceipt").value(false));
    }

    @Test
    @DisplayName("상세: 환불 주문 + 강의가 삭제된 항목은 courseId null, 이름은 스냅샷")
    void detailRefundedDeletedLecture() throws Exception {
        detail("10300")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.order.status").value("REFUNDED"))
                .andExpect(jsonPath("$.result.order.statusLabel").value("환불 완료"))
                .andExpect(jsonPath("$.result.order.items[0].courseId").value(nullValue()))
                .andExpect(jsonPath("$.result.order.items[0].title").value("삭제된 강의"))
                .andExpect(jsonPath("$.result.order.productAmount").value(30000))
                .andExpect(jsonPath("$.result.payment.cardLast4").value("2222"));
    }

    @Test
    @DisplayName("상세: 거절만 있는 pending 주문은 결제 대기, payment null")
    void detailPendingDeclined() throws Exception {
        detail("10400")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.order.status").value("PENDING"))
                .andExpect(jsonPath("$.result.order.statusLabel").value("결제 대기"))
                .andExpect(jsonPath("$.result.payment").value(nullValue()))
                .andExpect(jsonPath("$.result.hasReceipt").value(false));
    }

    @Test
    @DisplayName("상세: 남의 주문·없는 주문·잘못된 orderId는 모두 404 ORDER_NOT_FOUND (IDOR 방지)")
    void detailNotFound() throws Exception {
        for (String id : new String[]{"10500", "99999", "0", "-1", "abc", "1.5"}) {
            detail(id)
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"))
                    .andExpect(jsonPath("$.message").value("주문을 찾을 수 없습니다."))
                    .andExpect(jsonPath("$.result").value(nullValue()));
        }
        // 주인은 볼 수 있다
        mvc.perform(get("/mypage/buy/history/10500").header("Authorization", bearer(other)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.payment.cardLast4").value("4444"));
    }
}
