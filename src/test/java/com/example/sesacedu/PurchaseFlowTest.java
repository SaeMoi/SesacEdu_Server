package com.example.sesacedu;

import com.example.sesacedu.virtualpg.VpgLedger;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 구매 흐름 전체를 실제 API만 이어서 호출하는 흐름 테스트.
 * 토큰은 손으로 만들지 않는다: 이메일 인증 → 회원가입 → 로그인으로 받은 JWT를 그대로 쓴다.
 *   장바구니 담기·삭제 → 쿠폰 등록 → 나의해택 → 결제 화면(GET /mypage/buy) → 쿠폰 미리보기(GET /mypage/buy/cpn)
 *   → 주문(POST /mypage/buy) → 결제(POST /payment, 가상 PG 실제 HTTP) → 결제 내역·영수증 → 내 학습
 * 강좌·쿠폰은 아직 만드는 API(강사·관리자)가 없어 DB에 직접 넣는다.
 * 두 번째 회원이 첫 회원의 장바구니·쿠폰·주문·영수증을 보거나 쓸 수 없는지도 확인한다.
 * /payment가 가상 PG를 HTTP로 부르므로 RANDOM_PORT. CSV 원장은 실행마다 새 임시 파일.
 *   gradlew test --tests com.example.sesacedu.PurchaseFlowTest
 */
@SuppressWarnings("null") // VS Code(JDT) null 분석이 Spring/MockMvc API에 내는 경고 제외. 실행에는 영향 없음
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "JWT_SECRET=" + PurchaseFlowTest.SECRET,
        "vpg.secret=purchase-flow-vpg-secret",
        "vpg.ledger-path=${java.io.tmpdir}/sesacedu-vpg-flow/${random.uuid}/approvals.csv"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PurchaseFlowTest {

    static final String SECRET = "purchase-flow-test-secret-at-least-32-bytes!";
    static final String PW = "Passw0rd!";
    static final String NEXT_YEAR = String.format("12/%02d", (YearMonth.now().getYear() + 1) % 100);
    static final Timestamp FAR = Timestamp.valueOf("2099-12-31 23:59:59");

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired VpgLedger ledger;
    @Autowired TestRestTemplate rest;
    @MockBean JavaMailSender mailSender;

    static int ipSeq = 0;

    @BeforeEach
    void setUp() {
        for (String t : new String[]{"order_items", "orders", "user_coupons", "coupons", "cart", "enrollments", "lectures", "users"}) {
            jdbc.update("DELETE FROM " + t);
        }
        reset(mailSender);
        lecture(7, "리눅스 입문", 39000, "open");
        lecture(12, "웹 보안 기초", 49000, "open");
        lecture(16, "새 강의", 25000, "open");
        lecture(20, "준비 중인 강의", 10000, "draft");
        // 전체 강의용, 5만 원 이상 주문에만 (min_amount는 전체용 쿠폰에만 적용)
        coupon(9201, "WELCOME2026ABCD", null, 8000, 50000, 100, FAR);
    }

    // ------------------------------------------------------------ 데이터 (만드는 API가 아직 없음)

    void lecture(long lid, String name, int price, String status) {
        jdbc.update("INSERT INTO lectures (Lid, user_id, name, category, Lprice, status) VALUES (?, 9001, ?, '웹 보안', ?, ?)",
                lid, name, price, status);
    }

    void coupon(long cid, String code, Long lid, int discount, Integer minAmount, Integer quantity, Timestamp due) {
        jdbc.update("INSERT INTO coupons (cid, user_id, Lid, cname, code, discount, min_amount, quantity, issued, due)"
                + " VALUES (?, 9001, ?, ?, ?, ?, ?, ?, 0, ?)", cid, lid, "쿠폰 " + cid, code, discount, minAmount, quantity, due);
    }

    // ------------------------------------------------------------ API 호출 (응답 값을 그대로 돌려줌)

    /** 이메일 인증 → 회원가입 → 로그인. 로그인 응답 헤더의 "Bearer xxx" 그대로 */
    String member(String email, String nickname) throws Exception {
        String ip = "10.8.0." + (++ipSeq);
        mvc.perform(post("/email-auth").contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"" + email + "\"}")
                        .with(r -> { r.setRemoteAddr(ip); return r; }))
                .andExpect(status().isOk());
        ArgumentCaptor<SimpleMailMessage> c = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender, atLeastOnce()).send(c.capture());
        Matcher m = Pattern.compile("인증번호: ([0-9]{6})").matcher(c.getValue().getText());
        assertTrue(m.find());
        String vt = read(mvc.perform(post("/email-auth/verify").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"code\":\"" + m.group(1) + "\"}"))
                .andExpect(status().isOk()).andReturn(), "$.result.verificationToken");
        mvc.perform(post("/signup").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"verificationToken\":\"" + vt + "\",\"pw\":\"" + PW
                                + "\",\"pwcheck\":\"" + PW + "\",\"name\":\"김새싹\",\"nickname\":\"" + nickname
                                + "\",\"phone\":\"01011112222\",\"b_date\":\"2000-03-15\",\"address\":\"서울시 어딘가\"}"))
                .andExpect(status().isCreated());
        MvcResult r = login(email).andExpect(status().isOk()).andReturn();
        String h = r.getResponse().getHeader("Authorization");
        assertNotNull(h);
        return h;
    }

    ResultActions login(String email) throws Exception {
        return mvc.perform(post("/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"pw\":\"" + PW + "\"}"));
    }

    ResultActions get(String bearer, String url) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(url).header("Authorization", bearer));
    }

    ResultActions postJson(String bearer, String url, String json) throws Exception {
        return mvc.perform(post(url).header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    ResultActions cartAdd(String bearer, long lid) throws Exception {
        return postJson(bearer, "/mypage/cart/add", "{\"courseId\":" + lid + "}");
    }

    ResultActions cartDel(String bearer, String ids) throws Exception {
        return mvc.perform(delete("/mypage/cart/del").header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON).content("{\"courseIds\":[" + ids + "]}"));
    }

    ResultActions registerCoupon(String bearer, String code) throws Exception {
        return postJson(bearer, "/mypage/cpn", "{\"couponCode\":\"" + code + "\"}");
    }

    ResultActions order(String bearer, String ids, Long couponId, String payMethod) throws Exception {
        return postJson(bearer, "/mypage/buy", "{\"courseIds\":[" + ids + "]," + (couponId == null ? "" : "\"couponId\":" + couponId + ",")
                + "\"usePoint\":0,\"payMethod\":\"" + payMethod + "\"}");
    }

    long orderId(ResultActions created) throws Exception {
        return ((Number) JsonPath.read(body(created.andExpect(status().isCreated()).andReturn()), "$.result.orderId")).longValue();
    }

    static String cardJson(long orderId, String last4) {
        return "{\"orderId\":" + orderId + ",\"card\":{\"number\":\"" + card(last4) + "\",\"expiry\":\"" + NEXT_YEAR
                + "\",\"cvc\":\"123\",\"installment\":0}}";
    }

    ResultActions pay(String bearer, long orderId, String last4) throws Exception {
        return postJson(bearer, "/payment", cardJson(orderId, last4));
    }

    /** Luhn을 통과하는 16자리 카드 번호 (끝 4자리 지정). 0002 = 잔액 부족 거절, 그 밖은 승인 */
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

    static String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    static <T> T read(MvcResult r, String path) throws Exception {
        return JsonPath.read(body(r), path);
    }

    long enrollments(String email, long lid) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM enrollments e JOIN users u ON u.user_id = e.user_id"
                + " WHERE u.mail = ? AND e.Lid = ?", Long.class, email, lid);
    }

    long approvedLedgerRows(long orderId) throws Exception {
        Path p = ledger.path();
        List<String> lines = Files.exists(p) ? Files.readAllLines(p, StandardCharsets.UTF_8) : Collections.emptyList();
        return lines.stream().map(l -> l.split(",", -1))
                .filter(f -> f.length > 2 && f[1].equals(String.valueOf(orderId)) && f[2].equals("APPROVED")).count();
    }

    // ------------------------------------------------------------ 흐름

    @Test @DisplayName("한 회원의 구매 전체: 장바구니 → 쿠폰 등록 → 결제 화면 → 쿠폰 미리보기 → 주문 → 카드 결제 → 내역·영수증 → 내 학습")
    void purchaseJourney() throws Exception {
        String a = member("buyer@example.test", "구매자");

        // 1. 장바구니: 담기, 판매 중 아닌 강좌는 404, 중복은 409, 삭제 후 다시 담기
        cartAdd(a, 7).andExpect(status().isCreated()).andExpect(jsonPath("$.result.totalCount").value(1));
        cartAdd(a, 12).andExpect(status().isCreated());
        cartAdd(a, 16).andExpect(status().isCreated()).andExpect(jsonPath("$.result.totalCount").value(3));
        cartAdd(a, 20).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("COURSE_NOT_FOUND"));
        cartAdd(a, 7).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ALREADY_IN_CART"));
        cartDel(a, "16").andExpect(status().isOk())
                .andExpect(jsonPath("$.result.deletedCount").value(1)).andExpect(jsonPath("$.result.totalCount").value(2));
        get(a, "/mypage/cart").andExpect(status().isOk())
                .andExpect(jsonPath("$.result.summary.itemCount").value(2))
                .andExpect(jsonPath("$.result.summary.productAmount").value(88000));
        cartAdd(a, 16).andExpect(status().isCreated()).andExpect(jsonPath("$.result.totalCount").value(3));

        // 2. 쿠폰 등록 (소문자·하이픈은 정규화) → 나의해택에 보임
        registerCoupon(a, "welcome-2026-abcd").andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("COUPON_REGISTERED"))
                .andExpect(jsonPath("$.result.couponId").value(9201))
                .andExpect(jsonPath("$.result.discountValue").value(8000));
        get(a, "/mypage/bnf").andExpect(status().isOk())
                .andExpect(jsonPath("$.result.summary.availableCouponCount").value(1))
                .andExpect(jsonPath("$.result.coupons[0].couponId").value(9201));

        // 3. 결제 화면: 장바구니 3개, 쿠폰 적용 가능 (113,000 ≥ 50,000)
        get(a, "/mypage/buy").andExpect(status().isOk())
                .andExpect(jsonPath("$.result.itemCount").value(3))
                .andExpect(jsonPath("$.result.summary.productAmount").value(113000))
                .andExpect(jsonPath("$.result.coupons[0].applicable").value(true))
                .andExpect(jsonPath("$.result.coupons[0].expectedDiscount").value(8000));

        // 4. 두 강좌만 골라 쿠폰 미리보기 → 주문 금액과 같아야 함
        get(a, "/mypage/buy/cpn?courseIds=12,7&couponId=9201").andExpect(status().isOk())
                .andExpect(jsonPath("$.result.summary.productAmount").value(88000))
                .andExpect(jsonPath("$.result.summary.couponDiscountAmount").value(8000))
                .andExpect(jsonPath("$.result.summary.finalAmount").value(80000));

        // 5. 주문: 본문의 amount·price는 무시하고 서버가 계산
        long oid = orderId(postJson(a, "/mypage/buy",
                "{\"courseIds\":[12,7],\"couponId\":9201,\"usePoint\":0,\"payMethod\":\"CARD\",\"amount\":1,\"price\":1}")
                .andExpect(jsonPath("$.code").value("ORDER_CREATED"))
                .andExpect(jsonPath("$.result.amount").value(80000))
                .andExpect(jsonPath("$.result.orderName").value("웹 보안 기초 외 1개")));

        // pending 주문은 내역에 없고, 상세에는 결제 대기 / 쿠폰은 아직 쓰지 않음
        get(a, "/mypage/buy/history").andExpect(jsonPath("$.result.page.totalElements").value(0));
        get(a, "/mypage/buy/history/" + oid).andExpect(status().isOk())
                .andExpect(jsonPath("$.result.order.status").value("PENDING"))
                .andExpect(jsonPath("$.result.payment").value(nullValue()));
        get(a, "/mypage/bnf").andExpect(jsonPath("$.result.summary.availableCouponCount").value(1));

        // 6. 결제 (가상 PG 실제 HTTP)
        MvcResult paid = pay(a, oid, "1111").andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("PAYMENT_COMPLETED"))
                .andExpect(jsonPath("$.result.amount").value(80000))
                .andExpect(jsonPath("$.result.status").value("paid"))
                .andExpect(jsonPath("$.result.courseIds", contains(12, 7)))
                .andReturn();
        String paymentKey = read(paid, "$.result.paymentKey");
        assertNotNull(paymentKey);

        // 7. 결제 내역 → 영수증 (승인번호가 결제 응답과 같음)
        get(a, "/mypage/buy/history").andExpect(status().isOk())
                .andExpect(jsonPath("$.result.page.totalElements").value(1))
                .andExpect(jsonPath("$.result.orders[0].orderId").value(oid))
                .andExpect(jsonPath("$.result.orders[0].status").value("PAID"))
                .andExpect(jsonPath("$.result.orders[0].statusLabel").value("결제 완료"))
                .andExpect(jsonPath("$.result.orders[0].finalAmount").value(80000))
                .andExpect(jsonPath("$.result.orders[0].hasReceipt").value(true));
        get(a, "/mypage/buy/history/" + oid).andExpect(status().isOk())
                .andExpect(jsonPath("$.result.order.status").value("PAID"))
                .andExpect(jsonPath("$.result.order.productAmount").value(88000))
                .andExpect(jsonPath("$.result.order.couponDiscountAmount").value(8000))
                .andExpect(jsonPath("$.result.order.usePoint").value(0))
                .andExpect(jsonPath("$.result.order.finalAmount").value(80000))
                .andExpect(jsonPath("$.result.payment.paymentKey").value(paymentKey))
                .andExpect(jsonPath("$.result.payment.status").value("APPROVED"))
                .andExpect(jsonPath("$.result.payment.cardLast4").value("1111"))
                .andExpect(jsonPath("$.result.payment.amount").value(80000))
                .andExpect(jsonPath("$.result.hasReceipt").value(true));

        // 8. 내 학습에 두 강좌, 장바구니에는 주문하지 않은 강좌만, 쿠폰은 사용됨
        get(a, "/mypage/courses").andExpect(status().isOk())
                .andExpect(jsonPath("$.result.counts.all").value(2))
                .andExpect(jsonPath("$.result.courses[*].courseId", containsInAnyOrder(7, 12)))
                .andExpect(jsonPath("$.result.courses[*].status", everyItem(is("IN_PROGRESS"))));
        get(a, "/mypage/cart").andExpect(jsonPath("$.result.items[*].courseId", contains(16)));
        get(a, "/mypage/bnf").andExpect(jsonPath("$.result.summary.availableCouponCount").value(0));

        // 9. 산 강좌는 다시 못 담고, 쓴 쿠폰은 다시 못 쓰고, 같은 주문은 다시 결제 안 됨
        cartAdd(a, 7).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ALREADY_ENROLLED"));
        get(a, "/mypage/buy/cpn?courseIds=16&couponId=9201").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.result.reason").value("ALREADY_USED"));
        pay(a, oid, "1111").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ALREADY_PAID"));
        assertEquals(1, approvedLedgerRows(oid));
    }

    @Test @DisplayName("두 회원: B는 A의 장바구니·쿠폰·주문·영수증·내 학습을 보거나 쓸 수 없음")
    void secondMemberCannotTouchFirstMembersData() throws Exception {
        String a = member("owner@example.test", "주인");
        String b = member("other@example.test", "남의회원");

        cartAdd(a, 7).andExpect(status().isCreated());
        cartAdd(a, 12).andExpect(status().isCreated());
        cartAdd(a, 16).andExpect(status().isCreated());
        registerCoupon(a, "WELCOME2026ABCD").andExpect(status().isCreated());
        long paidOid = orderId(order(a, "7,12", 9201L, "CARD"));
        pay(a, paidOid, "1111").andExpect(status().isOk());
        long pendingOid = orderId(order(a, "16", null, "CARD"));

        // 조회: B에게는 아무것도 없음, A의 주문 번호는 404
        get(b, "/mypage/cart").andExpect(jsonPath("$.result.summary.itemCount").value(0));
        get(b, "/mypage/bnf").andExpect(jsonPath("$.result.summary.availableCouponCount").value(0));
        get(b, "/mypage/buy").andExpect(jsonPath("$.result.itemCount").value(0))
                .andExpect(jsonPath("$.result.coupons", hasSize(0)));
        get(b, "/mypage/buy/history").andExpect(jsonPath("$.result.page.totalElements").value(0));
        get(b, "/mypage/courses").andExpect(jsonPath("$.result.counts.all").value(0));
        for (long oid : new long[]{paidOid, pendingOid}) {
            get(b, "/mypage/buy/history/" + oid).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
        }

        // 결제: A의 pending 주문을 B가 결제할 수 없고, A의 주문은 그대로 pending
        pay(b, pendingOid, "1111").andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
        pay(b, paidOid, "1111").andExpect(status().isNotFound());
        get(a, "/mypage/buy/history/" + pendingOid).andExpect(jsonPath("$.result.order.status").value("PENDING"));
        assertEquals(0, enrollments("other@example.test", 16));

        // 주문: A 장바구니의 강좌로는 주문 불가, A의 쿠폰 번호는 B에게 「없는 쿠폰」
        order(b, "7", null, "CARD").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CART_CHANGED"))
                .andExpect(jsonPath("$.result.unavailableCourseIds", contains(7)));
        cartAdd(b, 16).andExpect(status().isCreated());
        get(b, "/mypage/buy/cpn?courseIds=16&couponId=9201").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.result.reason").value("COUPON_NOT_FOUND"));
        order(b, "16", 9201L, "CARD").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.result.reason").value("COUPON_NOT_FOUND"));

        // 장바구니 삭제는 B 자신의 행만: A의 16번은 남음
        cartDel(b, "7,12,16").andExpect(jsonPath("$.result.deletedCount").value(1))
                .andExpect(jsonPath("$.result.totalCount").value(0));
        get(a, "/mypage/cart").andExpect(jsonPath("$.result.items[*].courseId", contains(16)));

        // 같은 코드는 B도 따로 등록할 수 있고, A의 사용 여부와 무관하게 B 쿠폰은 사용 가능
        registerCoupon(b, "WELCOME2026ABCD").andExpect(status().isCreated());
        get(b, "/mypage/bnf").andExpect(jsonPath("$.result.summary.availableCouponCount").value(1));
        get(a, "/mypage/bnf").andExpect(jsonPath("$.result.summary.availableCouponCount").value(0));
    }

    @Test @DisplayName("쿠폰 규칙: 등록 오류·선착순 소진·강좌 전용·최소 금액, 할인이 가격보다 크면 0원 즉시 완료")
    void couponRules() throws Exception {
        coupon(9301, "COURSE7ONLY2026", 7L, 50000, 999999, null, FAR);      // 강좌 전용: min_amount는 보지 않음
        coupon(9302, "BIGORDERMIN2026", null, 3000, 100000, null, FAR);     // 전체용, 10만 원 이상
        coupon(9303, "LIMITEDONE2026", null, 5000, null, 1, FAR);           // 선착순 1장
        coupon(9304, "EXPIREDCODE2025", null, 5000, null, null, Timestamp.valueOf("2025-01-01 00:00:00"));
        String a = member("coupon-a@example.test", "쿠폰에이");
        String b = member("coupon-b@example.test", "쿠폰비");

        // 등록
        registerCoupon(a, "COURSE7ONLY2026").andExpect(status().isCreated())
                .andExpect(jsonPath("$.result.targetCourse.courseId").value(7));
        registerCoupon(a, "BIGORDERMIN2026").andExpect(status().isCreated());
        registerCoupon(a, "LIMITEDONE2026").andExpect(status().isCreated());
        registerCoupon(a, "COURSE7ONLY2026").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COUPON_ALREADY_REGISTERED"));
        registerCoupon(a, "EXPIREDCODE2025").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_COUPON_CODE"));
        registerCoupon(a, "NOSUCHCODE0000").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_COUPON_CODE"));
        registerCoupon(a, "short").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        registerCoupon(b, "LIMITEDONE2026").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("COUPON_SOLD_OUT"));
        get(b, "/mypage/bnf").andExpect(jsonPath("$.result.summary.availableCouponCount").value(0));
        assertEquals(1, jdbc.queryForObject("SELECT issued FROM coupons WHERE cid = 9303", Integer.class));

        cartAdd(a, 7).andExpect(status().isCreated());
        cartAdd(a, 12).andExpect(status().isCreated());
        cartAdd(a, 16).andExpect(status().isCreated());

        // 강좌 전용 쿠폰은 그 강좌가 주문에 있어야, 전체용은 최소 금액을 넘어야
        get(a, "/mypage/buy/cpn?courseIds=12&couponId=9301").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.result.reason").value("TARGET_COURSE_NOT_IN_ORDER"));
        get(a, "/mypage/buy/cpn?courseIds=7,12&couponId=9302").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.result.reason").value("MIN_AMOUNT_NOT_MET"))
                .andExpect(jsonPath("$.result.minAmount").value(100000));
        get(a, "/mypage/buy/cpn?courseIds=7,12,16&couponId=9302").andExpect(status().isOk())
                .andExpect(jsonPath("$.result.summary.finalAmount").value(113000 - 3000));

        // 강좌 전용 50,000원 쿠폰을 39,000원 강좌에: 할인은 가격까지만 → 0원 → PG 없이 바로 완료
        long freeOid = ((Number) JsonPath.read(body(order(a, "7", 9301L, "CARD").andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("ORDER_COMPLETED"))
                .andExpect(jsonPath("$.result.summary.couponDiscountAmount").value(39000))
                .andExpect(jsonPath("$.result.summary.finalAmount").value(0))
                .andReturn()), "$.result.orderId")).longValue();
        assertEquals(1, enrollments("coupon-a@example.test", 7));
        get(a, "/mypage/buy/history/" + freeOid)
                .andExpect(jsonPath("$.result.order.statusLabel").value("등록 완료"))
                .andExpect(jsonPath("$.result.payment").value(nullValue()))
                .andExpect(jsonPath("$.result.hasReceipt").value(false));
        assertEquals(0, approvedLedgerRows(freeOid));

        // 같은 쿠폰으로 pending 주문 두 개: 주문 때는 쿠폰을 묶지 않으므로 둘 다 생기고, 먼저 결제한 쪽만 쿠폰을 씀
        long o1 = orderId(order(a, "12", 9303L, "CARD"));
        long o2 = orderId(order(a, "16", 9303L, "CARD"));
        pay(a, o1, "1111").andExpect(status().isOk()).andExpect(jsonPath("$.result.amount").value(44000));
        pay(a, o2, "1111").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COUPON_NOT_APPLICABLE"))
                .andExpect(jsonPath("$.result.reason").value("ALREADY_USED"));
        get(a, "/mypage/buy/history/" + o2).andExpect(jsonPath("$.result.order.status").value("PENDING"));
        assertEquals(0, enrollments("coupon-a@example.test", 16));
        assertEquals(0, approvedLedgerRows(o2));
    }

    @Test @DisplayName("결제 중복 제출: 같은 주문을 동시에 두 번 보내면 하나만 승인, 수강 등록·원장 승인도 한 번")
    void doubleSubmitPayment() throws Exception {
        String a = member("double@example.test", "더블");
        cartAdd(a, 7).andExpect(status().isCreated());
        long oid = orderId(order(a, "7", null, "CARD"));

        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("Authorization", a);
        HttpEntity<String> req = new HttpEntity<>(cardJson(oid, "1111"), h);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<Integer>> results = new ArrayList<>();
        try {
            for (int i = 0; i < 2; i++) {
                Callable<Integer> call = () -> {
                    start.await();
                    ResponseEntity<String> res = rest.exchange("/payment", HttpMethod.POST, req, String.class);
                    return res.getStatusCodeValue();
                };
                results.add(pool.submit(call));
            }
            start.countDown();
            List<Integer> codes = new ArrayList<>();
            for (Future<Integer> f : results) codes.add(f.get(30, TimeUnit.SECONDS));
            Collections.sort(codes);
            assertEquals(List.of(200, 409), codes);
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, enrollments("double@example.test", 7));
        assertEquals(1, approvedLedgerRows(oid));
        get(a, "/mypage/buy/history").andExpect(jsonPath("$.result.page.totalElements").value(1));
    }

    @Test @DisplayName("주문 중복 제출: pending 주문이 두 개 생기지만, 하나를 결제하면 다른 하나는 CART_CHANGED로 막힘")
    void doubleSubmitOrder() throws Exception {
        String a = member("twice@example.test", "두번");
        cartAdd(a, 12).andExpect(status().isCreated());
        long o1 = orderId(order(a, "12", null, "CARD"));
        long o2 = orderId(order(a, "12", null, "CARD"));
        assertNotEquals(o1, o2);

        pay(a, o1, "1111").andExpect(status().isOk());
        pay(a, o2, "1111").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CART_CHANGED"))
                .andExpect(jsonPath("$.result.unavailableCourseIds", contains(12)));
        assertEquals(1, enrollments("twice@example.test", 12));
        assertEquals(0, approvedLedgerRows(o2));
        get(a, "/mypage/buy/history").andExpect(jsonPath("$.result.page.totalElements").value(1));
    }

    @Test @DisplayName("거절 후 재시도·계좌이체·주문 뒤 가격 변경·판매 중지: 주문은 pending으로 남고 금액은 주문 시점 값")
    void declineRetryAndChangesAfterOrder() throws Exception {
        String a = member("retry@example.test", "재시도");
        cartAdd(a, 7).andExpect(status().isCreated());
        cartAdd(a, 12).andExpect(status().isCreated());
        cartAdd(a, 16).andExpect(status().isCreated());

        // 카드 거절(잔액 부족) → 402, 아무것도 바뀌지 않음 → 다른 카드로 재시도 성공
        long o1 = orderId(order(a, "7", null, "CARD"));
        pay(a, o1, "0002").andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.code").value("PAYMENT_DECLINED"))
                .andExpect(jsonPath("$.result.reason").value("INSUFFICIENT_BALANCE"));
        get(a, "/mypage/buy/history/" + o1).andExpect(jsonPath("$.result.order.status").value("PENDING"));
        get(a, "/mypage/courses").andExpect(jsonPath("$.result.counts.all").value(0));
        get(a, "/mypage/cart").andExpect(jsonPath("$.result.summary.itemCount").value(3));
        pay(a, o1, "4242").andExpect(status().isOk());

        // 주문 뒤 강좌 가격이 올라도 결제는 주문 금액(계좌이체), 영수증은 계좌 뒤 4자리
        long o2 = orderId(order(a, "12", null, "TRANSFER"));
        jdbc.update("UPDATE lectures SET Lprice = 99000 WHERE Lid = 12");
        postJson(a, "/payment", "{\"orderId\":" + o2 + ",\"amount\":1,\"transfer\":{\"bankCode\":\"004\","
                + "\"accountNumber\":\"12345678905678\",\"holderName\":\"김새싹\"}}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.amount").value(49000))
                .andExpect(jsonPath("$.result.pay").value("TRANSFER"));
        get(a, "/mypage/buy/history/" + o2)
                .andExpect(jsonPath("$.result.payment.method").value("TRANSFER"))
                .andExpect(jsonPath("$.result.payment.accountLast4").value("5678"))
                .andExpect(jsonPath("$.result.payment.cardLast4").value(nullValue()));

        // 주문 뒤 판매 중지된 강좌는 결제 단계에서 막힘
        long o3 = orderId(order(a, "16", null, "CARD"));
        jdbc.update("UPDATE lectures SET status = 'closed' WHERE Lid = 16");
        pay(a, o3, "1111").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CART_CHANGED"))
                .andExpect(jsonPath("$.result.unavailableCourseIds", contains(16)));
        get(a, "/mypage/courses").andExpect(jsonPath("$.result.counts.all").value(2));
    }

    @Test @DisplayName("[현재 동작] 로그인 뒤 제한된 계정: 로그인·재발급은 403, 이미 받은 토큰으로는 주문·결제가 exp까지 그대로 됨")
    void restrictedAfterLogin() throws Exception {
        String a = member("ban-later@example.test", "나중제한");
        cartAdd(a, 7).andExpect(status().isCreated());
        jdbc.update("UPDATE users SET status = 'restricted' WHERE mail = 'ban-later@example.test'");

        login("ban-later@example.test").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCOUNT_RESTRICTED"));

        // 토큰에 status가 없고 mypage API는 users.status를 보지 않으므로 기존 토큰은 계속 동작한다.
        // 막기로 정하면 이 부분의 기대값을 403 ACCOUNT_RESTRICTED로 바꾼다.
        long oid = orderId(order(a, "7", null, "CARD"));
        pay(a, oid, "1111").andExpect(status().isOk());
    }
}
