package com.example.sesacedu;

import com.example.sesacedu.virtualpg.VpgLedger;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.YearMonth;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * POST /payment (mypage/payment/payment)와 POST /virtualPG/buy/confirm (virtualpg/vpg_confirm)이
 * Notion 명세(결제, 결제 승인(back ↔ PG사))대로 동작하는지 확인.
 * /payment는 가상 PG를 실제 HTTP로 부르므로 RANDOM_PORT로 Tomcat을 띄운다. 주문은 POST /mypage/buy로 만든다.
 * CSV 원장은 실행마다 새 임시 파일 (H2 oid가 실행마다 1부터라 원장을 이어 쓰면 멱등 결과가 섞인다).
 * 시간 초과(502) 경로는 읽기 타임아웃 2초 / 0009 카드 지연 4초로 줄여 확인한다.
 *   gradlew test --tests com.example.sesacedu.PaymentApiTest
 */
@SuppressWarnings("null") // VS Code(JDT) null 분석이 Spring/MockMvc API에 내는 경고 제외. 실행에는 영향 없음
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "JWT_SECRET=" + PaymentApiTest.SECRET,
        "vpg.secret=" + PaymentApiTest.VPG_SECRET,
        "vpg.ledger-path=${java.io.tmpdir}/sesacedu-vpg-test/${random.uuid}/approvals.csv",
        "vpg.read-timeout-ms=2000",
        "vpg.slow-delay-ms=4000"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PaymentApiTest {

    static final String SECRET = "payment-test-secret-at-least-32-bytes-long!!";
    static final String VPG_SECRET = "vpg-test-shared-secret";
    static final String GOOD_CARD = card("1111");
    static final String NEXT_YEAR = String.format("12/%02d", (YearMonth.now().getYear() + 1) % 100);

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired VpgLedger ledger;
    @Autowired ObjectMapper mapper;

    long me;
    long other;

    @BeforeEach
    void setUp() {
        for (String t : new String[]{"order_items", "orders", "user_coupons", "coupons", "cart", "enrollments", "lectures", "users"}) {
            jdbc.update("DELETE FROM " + t);
        }
        // users.point 기본값 0. 포인트 결제는 payWithPoints 에서 잔액을 준다
        jdbc.update("INSERT INTO users (mail, pw, uname, nickname, auth) VALUES ('pay-me@example.test', 'x', '김결제', '결제', 1)");
        jdbc.update("INSERT INTO users (mail, pw, uname, nickname, auth) VALUES ('pay-other@example.test', 'x', '이타인', '타인', 1)");
        me = id("pay-me@example.test");
        other = id("pay-other@example.test");

        lecture(7, "리눅스 입문", 39000);
        lecture(12, "웹 보안 기초", 49000);
        lecture(16, "새 강의", 25000);
        cart(me, 7);
        cart(me, 12);
        cart(me, 16);   // 주문에 안 넣는 강의: 결제 뒤에도 장바구니에 남아야 한다
        cart(other, 16);

        jdbc.update("INSERT INTO coupons (cid, user_id, Lid, cname, code, discount, quantity, issued, due) VALUES (9101, 9001, NULL, '전체 쿠폰', 'PAYCODE01', 8000, 10, 1, ?)",
                Timestamp.valueOf("2099-12-31 23:59:59"));
        jdbc.update("INSERT INTO user_coupons (user_id, cid, used) VALUES (?, 9101, 0)", me);
    }

    // ------------------------------------------------------------ helpers

    long id(String mail) {
        return Objects.requireNonNull(jdbc.queryForObject("SELECT user_id FROM users WHERE mail = ?", Long.class, mail));
    }

    void lecture(long lid, String name, int price) {
        jdbc.update("INSERT INTO lectures (Lid, user_id, name, category, Lprice, status) VALUES (?, 9001, ?, '웹 보안', ?, 'open')", lid, name, price);
    }

    void cart(long uid, long lid) {
        jdbc.update("INSERT INTO cart (user_id, Lid) VALUES (?, ?)", uid, lid);
    }

    static String bearer(long sub) {
        return "Bearer " + Jwts.builder().subject(String.valueOf(sub)).claim("role", "STUDENT").claim("reissue_num", 2)
                .expiration(new Date(System.currentTimeMillis() + 3600_000))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)), Jwts.SIG.HS256).compact();
    }

    /** Luhn을 통과하는 16자리 카드 번호 (끝 4자리 지정). 5번째 자리(오른쪽에서)를 맞춘다 */
    static String card(String last4) {
        for (int x = 0; x < 10; x++) {
            String n = "40000000000" + x + last4;
            int sum = 0;
            for (int i = 0; i < n.length(); i++) {
                int d = n.charAt(n.length() - 1 - i) - '0';
                if (i % 2 == 1) d = d * 2 > 9 ? d * 2 - 9 : d * 2;
                sum += d;
            }
            if (sum % 10 == 0) return n;
        }
        throw new IllegalStateException();
    }

    /** POST /mypage/buy로 pending 주문을 만들고 orderId를 돌려준다 */
    long order(String courseIds, Long couponId, long usePoint, String payMethod) throws Exception {
        String json = "{\"courseIds\":[" + courseIds + "]," + (couponId == null ? "" : "\"couponId\":" + couponId + ",")
                + "\"usePoint\":" + usePoint + ",\"payMethod\":\"" + payMethod + "\"}";
        MvcResult r = mvc.perform(post("/mypage/buy").header("Authorization", bearer(me))
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("ORDER_CREATED"))
                .andReturn();
        Map<?, ?> result = (Map<?, ?>) mapper.readValue(r.getResponse().getContentAsString(), Map.class).get("result");
        return ((Number) result.get("orderId")).longValue();
    }

    String cardBody(long orderId, String number) {
        return "{\"orderId\":" + orderId + ",\"card\":{\"number\":\"" + number + "\",\"expiry\":\"" + NEXT_YEAR + "\",\"cvc\":\"123\",\"installment\":0}}";
    }

    ResultActions pay(long uid, String json) throws Exception {
        return mvc.perform(post("/payment").header("Authorization", bearer(uid)).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    ResultActions vpg(String json) throws Exception {
        return mvc.perform(post("/virtualPG/buy/confirm").header("X-VPG-Secret", VPG_SECRET)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    String vpgCard(String oid, long amount, String number, String expiry) {
        return "{\"merchantOrderId\":\"" + oid + "\",\"amount\":" + amount + ",\"orderName\":\"웹 보안 기초\",\"method\":\"CARD\","
                + "\"card\":{\"number\":\"" + number + "\",\"expiry\":\"" + expiry + "\",\"cvc\":\"123\",\"installment\":0}}";
    }

    List<String> ledgerLines() throws Exception {
        Path p = ledger.path();
        return Files.exists(p) ? Files.readAllLines(p, StandardCharsets.UTF_8) : Collections.emptyList();
    }

    long ledgerRows(String merchantOrderId) throws Exception {
        return ledgerLines().stream().filter(l -> l.split(",", -1).length > 1 && l.split(",", -1)[1].equals(merchantOrderId)).count();
    }

    String orderStatus(long oid) {
        return jdbc.queryForObject("SELECT status FROM orders WHERE oid = ?", String.class, oid);
    }

    long count(String sql, Object... args) {
        return Objects.requireNonNull(jdbc.queryForObject(sql, Long.class, args));
    }

    /** POST /mypage/buy로는 만들 수 없는 주문 (잔액보다 큰 포인트 사용액이 남은 주문 등)을 직접 넣는다 */
    long rawOrder(long discount, long usePoint, long aprice, long lid, String lname, long price) {
        jdbc.update("INSERT INTO orders (user_id, discount, use_point, Aprice, pay, status) VALUES (?, ?, ?, ?, 'CARD', 'pending')",
                me, discount, usePoint, aprice);
        long oid = count("SELECT MAX(oid) FROM orders WHERE user_id = ?", me);
        jdbc.update("INSERT INTO order_items (oid, Lid, Lname, price) VALUES (?, ?, ?, ?)", oid, lid, lname, price);
        return oid;
    }

    // ------------------------------------------------------------ POST /virtualPG/buy/confirm

    @Test @DisplayName("가상 PG 401: 시크릿 없음·불일치, 시크릿이 맞아도 브라우저 Origin이 있으면 거부. CSV 기록 없음")
    void vpgUnauthorized() throws Exception {
        // Origin은 CORS 허용 목록(.env CORS_ALLOWED_ORIGINS)에 있는 값을 쓴다. 목록 밖이면 CORS가 403으로 먼저 막아 가상 PG의 Origin 거부(401)까지 오지 않는다.
        String json = vpgCard("700001", 1000, GOOD_CARD, NEXT_YEAR);
        mvc.perform(post("/virtualPG/buy/confirm").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("VPG_UNAUTHORIZED"))
                .andExpect(jsonPath("$.result").value(nullValue()));
        mvc.perform(post("/virtualPG/buy/confirm").header("X-VPG-Secret", "guess").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/virtualPG/buy/confirm").header("X-VPG-Secret", VPG_SECRET).header("Origin", "http://localhost:5173")
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isUnauthorized());
        assertEquals(0, ledgerRows("700001"));
    }

    @Test @DisplayName("가상 PG 400: 형식 오류·method 불일치·금액 0·문자열 금액은 CSV에 남기지 않는다")
    void vpgInvalid() throws Exception {
        vpg("not json").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VPG_INVALID_REQUEST"));
        vpg(vpgCard("700002", 0, GOOD_CARD, NEXT_YEAR)).andExpect(status().isBadRequest());
        vpg(vpgCard("700002", 1000, GOOD_CARD, "13/30")).andExpect(status().isBadRequest());
        vpg(vpgCard("700002", 1000, "4111-1111-1111-1111", NEXT_YEAR)).andExpect(status().isBadRequest());
        vpg("{\"merchantOrderId\":\"700002\",\"amount\":\"1000\",\"orderName\":\"x\",\"method\":\"CARD\","
                + "\"card\":{\"number\":\"" + GOOD_CARD + "\",\"expiry\":\"" + NEXT_YEAR + "\",\"cvc\":\"123\"}}").andExpect(status().isBadRequest());
        vpg("{\"merchantOrderId\":\"700002\",\"amount\":1000,\"orderName\":\"x\",\"method\":\"TRANSFER\","
                + "\"card\":{\"number\":\"" + GOOD_CARD + "\",\"expiry\":\"" + NEXT_YEAR + "\",\"cvc\":\"123\"}}").andExpect(status().isBadRequest());
        assertEquals(0, ledgerRows("700002"));
    }

    @Test @DisplayName("가상 PG 200 승인 → 같은 주문·같은 금액 재요청은 저장 결과 그대로(CSV 1행), 다른 금액은 409. 카드 원문·CVC는 CSV에 없음")
    void vpgApproveIdempotent() throws Exception {
        String json = vpgCard("700003", 80000, GOOD_CARD, NEXT_YEAR);
        MvcResult first = vpg(json)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("VPG_APPROVED"))
                .andExpect(jsonPath("$.result.status").value("APPROVED"))
                .andExpect(jsonPath("$.result.merchantOrderId").value("700003"))
                .andExpect(jsonPath("$.result.amount").value(80000))
                .andExpect(jsonPath("$.result.approvalId").value(matchesPattern("VPG-\\d{8}-\\d{6}")))
                .andExpect(jsonPath("$.result.cardMasked").value("4000-****-****-1111"))
                .andExpect(jsonPath("$.result.cardBrand").value("VISA"))
                .andExpect(jsonPath("$.result.reasonCode").value(nullValue()))
                .andExpect(jsonPath("$.result.approvedAt").value(endsWith("+09:00")))
                .andReturn();
        String approvalId = (String) ((Map<?, ?>) mapper.readValue(first.getResponse().getContentAsString(), Map.class).get("result")).get("approvalId");

        vpg(json).andExpect(status().isOk()).andExpect(jsonPath("$.result.approvalId").value(approvalId));
        vpg(vpgCard("700003", 1000, GOOD_CARD, NEXT_YEAR))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VPG_DUPLICATE_ORDER"))
                .andExpect(jsonPath("$.result.merchantOrderId").value("700003"));

        assertEquals(1, ledgerRows("700003"));
        List<String> lines = ledgerLines();
        assertTrue(lines.get(0).startsWith("approval_id,merchant_order_id,status,amount,currency,"));
        String all = String.join("\n", lines);
        assertFalse(all.contains(GOOD_CARD), "카드 번호 전체가 CSV에 있으면 안 된다");
        assertFalse(all.contains(NEXT_YEAR), "유효기간이 CSV에 있으면 안 된다");
        assertTrue(all.contains(approvalId + ",700003,APPROVED,80000,KRW,CARD,VISA,1111,0,,,,"));
    }

    @Test @DisplayName("가상 PG 거절 규칙: Luhn·만료·은행 코드·한도·테스트 번호 0002/0003. 거절 뒤 같은 주문 재요청은 다시 판단")
    void vpgDeclineRules() throws Exception {
        vpg(vpgCard("700010", 1000, "4000000000001112", NEXT_YEAR)).andExpect(jsonPath("$.result.reasonCode").value("INVALID_CARD"));
        vpg(vpgCard("700011", 1000, GOOD_CARD, "01/20")).andExpect(jsonPath("$.result.reasonCode").value("EXPIRED_CARD"));
        vpg(vpgCard("700012", 1_000_001, GOOD_CARD, NEXT_YEAR)).andExpect(jsonPath("$.result.reasonCode").value("LIMIT_EXCEEDED"));
        vpg(vpgCard("700013", 1000, card("0003"), NEXT_YEAR)).andExpect(jsonPath("$.result.reasonCode").value("LIMIT_EXCEEDED"));
        vpg("{\"merchantOrderId\":\"700014\",\"amount\":1000,\"orderName\":\"x\",\"method\":\"TRANSFER\","
                + "\"transfer\":{\"bankCode\":\"999\",\"accountNumber\":\"1234567890\",\"holderName\":\"김결제\"}}")
                .andExpect(jsonPath("$.result.reasonCode").value("INVALID_ACCOUNT"));

        vpg(vpgCard("700015", 1000, card("0002"), NEXT_YEAR))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("VPG_DECLINED"))
                .andExpect(jsonPath("$.result.status").value("DECLINED"))
                .andExpect(jsonPath("$.result.reasonCode").value("INSUFFICIENT_BALANCE"))
                .andExpect(jsonPath("$.result.approvedAt").value(nullValue()));
        vpg(vpgCard("700015", 1000, GOOD_CARD, NEXT_YEAR))
                .andExpect(jsonPath("$.code").value("VPG_APPROVED"));
        assertEquals(2, ledgerRows("700015"));
    }

    @Test @DisplayName("가상 PG CSV 이스케이프: X-Request-Id가 =로 시작하고 쉼표가 있으면 ' 접두 + 큰따옴표")
    void vpgCsvEscape() throws Exception {
        mvc.perform(post("/virtualPG/buy/confirm").header("X-VPG-Secret", VPG_SECRET).header("X-Request-Id", "=HYPERLINK(\"x\",1)")
                        .contentType(MediaType.APPLICATION_JSON).content(vpgCard("700020", 1000, GOOD_CARD, NEXT_YEAR)))
                .andExpect(status().isOk());
        String row = ledgerLines().stream().filter(l -> l.contains(",700020,")).findFirst().orElseThrow(AssertionError::new);
        assertTrue(row.endsWith(",\"'=HYPERLINK(\"\"x\"\",1)\""), row);
    }

    // ------------------------------------------------------------ POST /payment

    @Test @DisplayName("결제 401: 토큰 없음")
    void payUnauthorized() throws Exception {
        mvc.perform(post("/payment").contentType(MediaType.APPLICATION_JSON).content("{\"orderId\":1}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test @DisplayName("결제 400: orderId 없음·양의 정수 아님, 깨진 JSON")
    void payInvalidOrderId() throws Exception {
        pay(me, "{}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.result.errors[0].field").value("orderId"));
        pay(me, "{\"orderId\":\"10\"}").andExpect(status().isBadRequest());
        pay(me, "{\"orderId\":").andExpect(status().isBadRequest()).andExpect(jsonPath("$.result.errors[0].field").value("body"));
    }

    @Test @DisplayName("결제 404: 남의 주문·없는 주문은 똑같이 ORDER_NOT_FOUND (IDOR 방지)")
    void payNotFound() throws Exception {
        long oid = order("12", null, 0, "CARD");
        pay(other, cardBody(oid, GOOD_CARD)).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
        pay(me, cardBody(oid + 1000, GOOD_CARD)).andExpect(status().isNotFound());
        assertEquals("pending", orderStatus(oid));
        assertEquals(0, ledgerRows(String.valueOf(oid)));
    }

    @Test @DisplayName("결제 200 카드: 금액은 Aprice만(본문 amount 무시), paid + 수강 등록 + 쿠폰 사용 + 주문 강좌만 장바구니 삭제. 두 번째는 409")
    void payCard() throws Exception {
        // 39000 + 49000 = 88000, 쿠폰 8000 → Aprice 80000
        long oid = order("12, 7", 9101L, 0, "CARD");
        String json = "{\"orderId\":" + oid + ",\"card\":{\"number\":\"" + GOOD_CARD.replaceAll("(\\d{4})(?=\\d)", "$1-")
                + "\",\"expiry\":\"" + NEXT_YEAR + "\",\"cvc\":\"123\"}}";
        pay(me, json)
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("PAYMENT_COMPLETED"))
                .andExpect(jsonPath("$.message").value("결제가 완료되었습니다."))
                .andExpect(jsonPath("$.result.orderId").value(oid))
                .andExpect(jsonPath("$.result.paymentKey").value(matchesPattern("VPG-\\d{8}-\\d{6}")))
                .andExpect(jsonPath("$.result.amount").value(80000))
                .andExpect(jsonPath("$.result.pay").value("CARD"))
                .andExpect(jsonPath("$.result.status").value("paid"))
                .andExpect(jsonPath("$.result.courseIds", contains(12, 7)))
                .andExpect(jsonPath("$.result.approvedAt").value(endsWith("+09:00")));

        assertEquals("paid", orderStatus(oid));
        assertEquals(1, count("SELECT COUNT(*) FROM orders WHERE oid = ? AND paid_at IS NOT NULL", oid));
        assertEquals(2, count("SELECT COUNT(*) FROM enrollments WHERE user_id = ? AND Lid IN (7, 12)", me));
        assertEquals(1, count("SELECT used FROM user_coupons WHERE user_id = ? AND cid = 9101", me));
        assertEquals(1, count("SELECT COUNT(*) FROM cart WHERE user_id = ?", me));
        assertEquals(1, count("SELECT COUNT(*) FROM cart WHERE user_id = ? AND Lid = 16", me));
        assertEquals(1, count("SELECT COUNT(*) FROM cart WHERE user_id = ?", other));
        assertTrue(ledgerLines().stream().anyMatch(l -> l.contains("," + oid + ",APPROVED,80000,KRW,CARD,")));

        pay(me, cardBody(oid, GOOD_CARD)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_PAID"))
                .andExpect(jsonPath("$.result").value(nullValue()));
        assertEquals(1, ledgerRows(String.valueOf(oid)));
    }

    @Test @DisplayName("[의도된 취약점 A04/A08] 결제 금액 조작: 본문 amount가 주문 금액(Aprice) 대신 승인·원장에 쓰인다")
    void payPriceTamper() throws Exception {
        // 주문 금액(Aprice)은 80000이지만, 본문 amount=100으로 승인받아 100원만 결제된다
        long oid = order("12, 7", 9101L, 0, "CARD");
        String json = "{\"orderId\":" + oid + ",\"amount\":100,\"card\":{\"number\":\"" + GOOD_CARD
                + "\",\"expiry\":\"" + NEXT_YEAR + "\",\"cvc\":\"123\",\"installment\":0}}";
        pay(me, json)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("PAYMENT_COMPLETED"))
                .andExpect(jsonPath("$.result.status").value("paid"));
        assertEquals("paid", orderStatus(oid));
        // 원장(가상 PG)에 조작된 금액 100이 APPROVED 로 기록된다 — 가격 조작 성공
        assertTrue(ledgerLines().stream().anyMatch(l -> l.contains(",APPROVED,100,KRW,CARD,")),
                "본문 amount=100이 그대로 승인되어 원장에 100으로 남아야 한다");
    }

    @Test @DisplayName("결제 200 계좌이체")
    void payTransfer() throws Exception {
        long oid = order("16", null, 0, "TRANSFER");
        pay(me, "{\"orderId\":" + oid + ",\"transfer\":{\"bankCode\":\"088\",\"accountNumber\":\"110123456789\",\"holderName\":\"김결제\"}}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.amount").value(25000))
                .andExpect(jsonPath("$.result.pay").value("TRANSFER"))
                .andExpect(jsonPath("$.result.courseIds", contains(16)));
        assertEquals("paid", orderStatus(oid));
        assertTrue(ledgerLines().stream().anyMatch(l -> l.contains("," + oid + ",APPROVED,25000,KRW,TRANSFER,,,,088,6789,")));
        assertFalse(String.join("\n", ledgerLines()).contains("110123456789"));
    }

    @Test @DisplayName("결제 400: orders.pay와 다른 결제 정보, 카드 형식 오류. 가상 PG를 부르지 않는다")
    void payInfoInvalid() throws Exception {
        long oid = order("12", null, 0, "CARD");
        pay(me, "{\"orderId\":" + oid + ",\"transfer\":{\"bankCode\":\"088\",\"accountNumber\":\"110123456789\",\"holderName\":\"김\"}}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.result.errors[*].field", hasItems("transfer", "card")));
        pay(me, "{\"orderId\":" + oid + ",\"card\":{\"number\":\"12ab\",\"expiry\":\"2028-12\",\"cvc\":\"12\",\"installment\":1}}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.result.errors[*].field", contains("card.number", "card.expiry", "card.cvc", "card.installment")));
        assertEquals(0, ledgerRows(String.valueOf(oid)));
        assertEquals("pending", orderStatus(oid));
    }

    @Test @DisplayName("결제 402: 가상 PG 거절이면 아무것도 바뀌지 않고 pending → 다른 카드로 재시도하면 200")
    void payDeclinedThenRetry() throws Exception {
        long oid = order("12", 9101L, 0, "CARD");
        pay(me, cardBody(oid, card("0002")))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.code").value("PAYMENT_DECLINED"))
                .andExpect(jsonPath("$.result.orderId").value(oid))
                .andExpect(jsonPath("$.result.reason").value("INSUFFICIENT_BALANCE"));
        pay(me, cardBody(oid, "4000000000001112"))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.result.reason").value("INVALID_CARD"));
        assertEquals("pending", orderStatus(oid));
        assertEquals(0, count("SELECT COUNT(*) FROM enrollments WHERE user_id = ?", me));
        assertEquals(0, count("SELECT used FROM user_coupons WHERE user_id = ? AND cid = 9101", me));
        assertEquals(3, count("SELECT COUNT(*) FROM cart WHERE user_id = ?", me));

        pay(me, cardBody(oid, GOOD_CARD)).andExpect(status().isOk()).andExpect(jsonPath("$.result.amount").value(41000));
        assertEquals(1, count("SELECT used FROM user_coupons WHERE user_id = ? AND cid = 9101", me));
    }

    @Test @DisplayName("결제 409: 주문 뒤 강의가 내려가면 CART_CHANGED, 잔액보다 큰 포인트 사용액이 남은 주문은 INSUFFICIENT_POINT, 쿠폰을 써 버리면 COUPON_NOT_APPLICABLE")
    void payConflicts() throws Exception {
        long closed = order("7", null, 0, "CARD");
        long points = rawOrder(0, 4000, 45000, 12, "웹 보안 기초", 49000);   // 잔액 0인데 use_point 4000
        long coupon = order("16", 9101L, 0, "CARD");

        jdbc.update("UPDATE lectures SET status = 'closed' WHERE Lid = 7");
        pay(me, cardBody(closed, GOOD_CARD)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CART_CHANGED"))
                .andExpect(jsonPath("$.result.unavailableCourseIds", contains(7)));

        pay(me, cardBody(points, GOOD_CARD)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_POINT"))
                .andExpect(jsonPath("$.result.balance").value(0))
                .andExpect(jsonPath("$.result.usePoint").value(4000));

        jdbc.update("UPDATE user_coupons SET used = 1 WHERE user_id = ? AND cid = 9101", me);
        pay(me, cardBody(coupon, GOOD_CARD)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COUPON_NOT_APPLICABLE"))
                .andExpect(jsonPath("$.result.reason").value("ALREADY_USED"));

        for (long oid : new long[]{closed, points, coupon}) {
            assertEquals("pending", orderStatus(oid));
            assertEquals(0, ledgerRows(String.valueOf(oid)));
        }
    }

    @Test @DisplayName("결제 200 포인트: 가상 PG에는 Aprice만, 승인 뒤 users.point에서 orders.use_point만큼 차감. 주문 뒤 잔액이 줄면 409 INSUFFICIENT_POINT")
    void payWithPoints() throws Exception {
        jdbc.update("UPDATE users SET point = 10000 WHERE user_id = ?", me);
        long oid = order("12", null, 3000, "CARD");
        assertEquals(3000, count("SELECT use_point FROM orders WHERE oid = ?", oid));

        jdbc.update("UPDATE users SET point = 2000 WHERE user_id = ?", me);   // 주문 뒤 다른 곳에서 써 버림
        pay(me, cardBody(oid, GOOD_CARD)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_POINT"))
                .andExpect(jsonPath("$.result.balance").value(2000))
                .andExpect(jsonPath("$.result.usePoint").value(3000));
        assertEquals("pending", orderStatus(oid));
        assertEquals(0, ledgerRows(String.valueOf(oid)));

        jdbc.update("UPDATE users SET point = 10000 WHERE user_id = ?", me);
        pay(me, cardBody(oid, GOOD_CARD)).andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("PAYMENT_COMPLETED"))
                .andExpect(jsonPath("$.result.amount").value(46000));
        assertEquals("paid", orderStatus(oid));
        assertEquals(7000, count("SELECT point FROM users WHERE user_id = ?", me));
        assertEquals(1, count("SELECT COUNT(*) FROM enrollments WHERE user_id = ? AND Lid = 12", me));
    }

    @Test @DisplayName("결제 502: 가상 PG 시간 초과면 pending 유지 → 재요청은 가상 PG 멱등 결과로 200 (이중 승인 없음)")
    void payTimeoutThenRetry() throws Exception {
        long oid = order("12", null, 0, "CARD");
        pay(me, cardBody(oid, card("0009")))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("PG_UNAVAILABLE"))
                .andExpect(jsonPath("$.result.orderId").value(oid));
        assertEquals("pending", orderStatus(oid));
        assertEquals(0, count("SELECT COUNT(*) FROM enrollments WHERE user_id = ?", me));

        // 가상 PG는 지연 뒤 승인으로 기록한다. 기록이 생긴 뒤 재요청하면 저장된 승인을 받는다
        for (int i = 0; i < 50 && ledgerRows(String.valueOf(oid)) == 0; i++) Thread.sleep(100);
        pay(me, cardBody(oid, card("0009"))).andExpect(status().isOk()).andExpect(jsonPath("$.result.status").value("paid"));
        assertEquals(1, ledgerRows(String.valueOf(oid)));
        assertEquals("paid", orderStatus(oid));
    }
}
