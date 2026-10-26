package com.example.sesacedu;

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

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.Map;
import java.util.Objects;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * mypage/buy 패키지의 buy / buy_cpn / buy_pnt / buy_order가 Notion 명세(결제 인터페이스 GET·POST, 결제 쿠폰·포인트 적용)대로 응답하는지 확인.
 * H2(Oracle 모드, application-test.yml + schema-test.sql)로 실행:
 *   gradlew test --tests com.example.sesacedu.MypageBuyApiTest
 */
@SuppressWarnings("null") // VS Code(JDT) null 분석이 Spring/MockMvc API에 내는 경고 제외. 실행에는 영향 없음
@SpringBootTest(properties = "JWT_SECRET=" + MypageBuyApiTest.SECRET)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MypageBuyApiTest {

    static final String SECRET = "mypage-buy-test-secret-at-least-32-bytes-long!!";
    static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    long me;
    long other;
    long empty;

    @BeforeEach
    void setUp() {
        for (String t : new String[]{"order_items", "orders", "user_coupons", "coupons", "cart", "enrollments", "lectures", "users"}) {
            jdbc.update("DELETE FROM " + t);
        }
        jdbc.update("INSERT INTO users (mail, pw, uname, nickname, auth) VALUES ('me@example.test', 'x', '김새싹', '새싹', 1)");
        jdbc.update("INSERT INTO users (mail, pw, uname, nickname, auth) VALUES ('other@example.test', 'x', '이타인', '타인', 1)");
        jdbc.update("INSERT INTO users (mail, pw, uname, nickname, auth) VALUES ('empty@example.test', 'x', '빈사람', '빈', 0)");
        me = id("me@example.test");
        other = id("other@example.test");
        empty = id("empty@example.test");
        // users.point 기본값 0. 포인트가 있는 경우는 point 테스트에서 UPDATE 로 준다

        lecture(7, "리눅스 입문", 39000, "open");
        lecture(12, "웹 보안 기초", 49000, "open");
        lecture(14, "내린 강의", 20000, "closed");
        lecture(15, "산 강의", 30000, "open");
        lecture(16, "새 강의", 25000, "open");
        lecture(20, "보안 심화 실습", 60000, "open");

        cart(me, 7);
        cart(me, 12);
        cart(me, 14);    // closed → purchasable false, 합계·orderName 제외
        cart(other, 16);
        jdbc.update("INSERT INTO enrollments (user_id, Lid, done) VALUES (?, 15, 0)", me);

        // 9001 전체, 9002 강의 12 전용, 9003 만료, 9004 사용함, 9005 남의 것, 9008 강의 7 전용·가격 초과 할인, 9009 장바구니에 없는 강의 20 전용
        coupon(9001, null, "첫 학습 응원 쿠폰", 5000, "2099-10-31 23:59:59");
        coupon(9002, 12L, "웹 보안 기초 쿠폰", 10000, "2099-10-20 23:59:59");
        coupon(9003, null, "지난 쿠폰", 3000, "2020-01-01 00:00:00");
        coupon(9004, null, "써 버린 쿠폰", 2000, "2099-12-31 00:00:00");
        coupon(9005, null, "남의 쿠폰", 1000, "2099-01-01 00:00:00");
        coupon(9008, 7L, "리눅스 통째 쿠폰", 88000, "2099-11-30 23:59:59");  // 강의 7(39000) 전용인데 할인 88000 → un-clamp 시 주문 전체(88000)를 0원으로
        coupon(9009, 20L, "보안 심화 전용 쿠폰", 3000, "2099-12-31 23:59:59");
        own(me, 9001, 0);
        own(me, 9002, 0);
        own(me, 9003, 0);
        own(me, 9004, 1);
        own(other, 9005, 0);
        own(me, 9008, 0);
        own(me, 9009, 0);
    }

    long id(String mail) {
        return Objects.requireNonNull(jdbc.queryForObject("SELECT user_id FROM users WHERE mail = ?", Long.class, mail));
    }

    void lecture(long lid, String name, int price, String status) {
        jdbc.update("INSERT INTO lectures (Lid, user_id, name, category, Lprice, status) VALUES (?, 9001, ?, '웹 보안', ?, ?)",
                lid, name, price, status);
    }

    void cart(long uid, long lid) {
        jdbc.update("INSERT INTO cart (user_id, Lid) VALUES (?, ?)", uid, lid);
    }

    void coupon(long cid, Long lid, String name, int discount, String due) {
        jdbc.update("INSERT INTO coupons (cid, user_id, Lid, cname, code, discount, quantity, issued, due) VALUES (?, 9001, ?, ?, ?, ?, 10, 1, ?)",
                cid, lid, name, "CODE" + cid + "ABCDE", discount, Timestamp.valueOf(due));
    }

    void own(long uid, long cid, int used) {
        jdbc.update("INSERT INTO user_coupons (user_id, cid, used) VALUES (?, ?, ?)", uid, cid, used);
    }

    long unusedCoupons(long uid) {
        return Objects.requireNonNull(jdbc.queryForObject("SELECT COUNT(*) FROM user_coupons WHERE user_id = ? AND used = 0", Long.class, uid));
    }

    static String kst(String ts) {
        return Timestamp.valueOf(ts).toInstant().atZone(ZoneId.of("Asia/Seoul")).format(ISO);
    }

    static String token(long sub, long secondsAgo, String secret) {
        return Jwts.builder().subject(String.valueOf(sub)).claim("role", "STUDENT").claim("reissue_num", 2)
                .expiration(new Date(System.currentTimeMillis() - secondsAgo * 1000))
                .signWith(Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8)), Jwts.SIG.HS256).compact();
    }

    static String bearer(long sub) {
        return "Bearer " + token(sub, -3600, SECRET);
    }

    // ------------------------------------------------------------ GET /mypage/buy

    @Test @DisplayName("결제 인터페이스 200: 장바구니 전체, 구매 가능 항목만 합계·orderName, 쿠폰 applicable·expectedDiscount, 포인트")
    void checkout() throws Exception {
        mvc.perform(get("/mypage/buy").header("Authorization", bearer(me)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("CHECKOUT_ITEMS_FOUND"))
                .andExpect(jsonPath("$.message").value("결제할 강의를 조회했습니다."))
                .andExpect(jsonPath("$.result.items[*].courseId", contains(14, 12, 7)))
                .andExpect(jsonPath("$.result.items[0].purchasable").value(false))
                .andExpect(jsonPath("$.result.items[1].title").value("웹 보안 기초"))
                .andExpect(jsonPath("$.result.items[1].price").value(49000))
                .andExpect(jsonPath("$.result.items[1].purchasable").value(true))
                .andExpect(jsonPath("$.result.itemCount").value(2))
                // 미사용·미만료·내 것만, 할인액 큰 순
                .andExpect(jsonPath("$.result.coupons[*].couponId", contains(9008, 9002, 9001, 9009)))
                .andExpect(jsonPath("$.result.coupons[0].discountValue").value(88000))
                .andExpect(jsonPath("$.result.coupons[0].applicable").value(true))
                .andExpect(jsonPath("$.result.coupons[0].expectedDiscount").value(88000))   // [의도된 취약점] clamp 제거: 강의 7 가격(39000)으로 자르지 않고 88000 그대로
                .andExpect(jsonPath("$.result.coupons[1].targetCourse.courseId").value(12))
                .andExpect(jsonPath("$.result.coupons[1].targetCourse.title").value("웹 보안 기초"))
                .andExpect(jsonPath("$.result.coupons[1].expectedDiscount").value(10000))
                .andExpect(jsonPath("$.result.coupons[2].name").value("첫 학습 응원 쿠폰"))
                .andExpect(jsonPath("$.result.coupons[2].targetCourse").value(nullValue()))
                .andExpect(jsonPath("$.result.coupons[2].expiresAt").value(kst("2099-10-31 23:59:59")))
                .andExpect(jsonPath("$.result.coupons[2].applicable").value(true))
                .andExpect(jsonPath("$.result.coupons[2].expectedDiscount").value(5000))
                .andExpect(jsonPath("$.result.coupons[2].length()").value(7))
                .andExpect(jsonPath("$.result.coupons[3].targetCourse.title").value("보안 심화 실습"))
                .andExpect(jsonPath("$.result.coupons[3].applicable").value(false))
                .andExpect(jsonPath("$.result.coupons[3].expectedDiscount").value(0))
                .andExpect(jsonPath("$.result.point.balance").value(0))     // 포인트 보류: 블록은 남고 0
                .andExpect(jsonPath("$.result.point.maxUsable").value(0))
                .andExpect(jsonPath("$.result.orderName").value("웹 보안 기초 외 1개"))
                .andExpect(jsonPath("$.result.payMethods[*].code", contains("CARD", "TRANSFER")))
                .andExpect(jsonPath("$.result.payMethods[1].label").value("계좌이체"))
                .andExpect(jsonPath("$.result.summary.productAmount").value(88000))
                .andExpect(jsonPath("$.result.summary.couponDiscountAmount").value(0))
                .andExpect(jsonPath("$.result.summary.usePoint").value(0))
                .andExpect(jsonPath("$.result.summary.finalAmount").value(88000));
        assertEquals(5, unusedCoupons(me)); // 조회만, 차감 없음
    }

    @Test @DisplayName("결제 인터페이스 200: 빈 장바구니는 items: [], orderName null, maxUsable 0, 강좌 1개면 orderName = 강좌명")
    void checkoutEmptyAndSingle() throws Exception {
        mvc.perform(get("/mypage/buy").header("Authorization", bearer(empty)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.items", hasSize(0)))
                .andExpect(jsonPath("$.result.itemCount").value(0))
                .andExpect(jsonPath("$.result.coupons", hasSize(0)))
                .andExpect(jsonPath("$.result.point.balance").value(0))
                .andExpect(jsonPath("$.result.point.maxUsable").value(0))
                .andExpect(jsonPath("$.result.orderName").value(nullValue()))
                .andExpect(jsonPath("$.result.summary.finalAmount").value(0));
        mvc.perform(get("/mypage/buy").header("Authorization", bearer(other)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.orderName").value("새 강의"))
                .andExpect(jsonPath("$.result.coupons[*].couponId", contains(9005)))
                .andExpect(jsonPath("$.result.point.maxUsable").value(0));
    }

    @Test @DisplayName("결제 인터페이스 401: 토큰 없음 UNAUTHORIZED, 만료 TOKEN_EXPIRED")
    void checkoutUnauthorized() throws Exception {
        mvc.perform(get("/mypage/buy"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.result").value(nullValue()));
        mvc.perform(get("/mypage/buy").header("Authorization", "Bearer " + token(me, 60, SECRET)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TOKEN_EXPIRED"));
    }

    // ------------------------------------------------------------ GET /mypage/buy/cpn

    @Test @DisplayName("쿠폰 적용 200: 전체용은 합계에서 할인, 중복 courseId 제거, DB 변경 없음")
    void couponApplied() throws Exception {
        mvc.perform(get("/mypage/buy/cpn?courseIds=12,7,12&couponId=9001").header("Authorization", bearer(me)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("COUPON_APPLIED"))
                .andExpect(jsonPath("$.message").value("쿠폰을 적용했습니다."))
                .andExpect(jsonPath("$.result.summary.productAmount").value(88000))
                .andExpect(jsonPath("$.result.summary.couponDiscountAmount").value(5000))
                .andExpect(jsonPath("$.result.summary.usePoint").value(0))
                .andExpect(jsonPath("$.result.summary.finalAmount").value(83000))
                .andExpect(jsonPath("$.result.appliedCouponId").value(9001));
        assertEquals(5, unusedCoupons(me));
    }

    @Test @DisplayName("쿠폰 적용 200: [의도된 취약점 A04] 전용 쿠폰 할인을 강좌 가격으로 자르지 않는다 (clamp 제거)")
    void couponClamped() throws Exception {
        // 12+7=88000, 강의 7 전용 쿠폰(할인 88000) → clamp라면 39000이지만 un-clamp라 88000 그대로 → 0원
        mvc.perform(get("/mypage/buy/cpn?courseIds=12,7&couponId=9008").header("Authorization", bearer(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.summary.couponDiscountAmount").value(88000))
                .andExpect(jsonPath("$.result.summary.finalAmount").value(0));
        // 7만(39000)에 88000 할인 → finalAmount 음수까지 그대로 (미리보기는 거절하지 않음)
        mvc.perform(get("/mypage/buy/cpn?courseIds=7&couponId=9008").header("Authorization", bearer(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.summary.finalAmount").value(-49000));
    }

    @Test @DisplayName("쿠폰 적용 400 COUPON_NOT_APPLICABLE: 없음·남의 것 / 만료 / 사용함 / 전용 강좌가 주문에 없음")
    void couponNotApplicable() throws Exception {
        String[][] cases = {
                {"7", "99999", "COUPON_NOT_FOUND"},
                {"7", "9005", "COUPON_NOT_FOUND"},
                {"7", "9003", "EXPIRED"},
                {"7", "9004", "ALREADY_USED"},
                {"7", "9002", "TARGET_COURSE_NOT_IN_ORDER"},
        };
        for (String[] c : cases) {
            mvc.perform(get("/mypage/buy/cpn?courseIds=" + c[0] + "&couponId=" + c[1]).header("Authorization", bearer(me)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("COUPON_NOT_APPLICABLE"))
                    .andExpect(jsonPath("$.message").value("이 주문에 사용할 수 없는 쿠폰입니다."))
                    .andExpect(jsonPath("$.result.reason").value(c[2]));
        }
    }

    @Test @DisplayName("min_amount: 전체용 쿠폰만 주문 합계와 비교, 강좌 전용 쿠폰은 min_amount 무시")
    void couponMinAmount() throws Exception {
        coupon(9010, null, "9만원 이상 쿠폰", 2000, "2099-12-31 23:59:59");
        coupon(9011, 7L, "리눅스 전용 최소금액 쿠폰", 1000, "2099-12-31 23:59:59");
        jdbc.update("UPDATE coupons SET min_amount = 90000 WHERE cid = 9010");
        jdbc.update("UPDATE coupons SET min_amount = 100000 WHERE cid = 9011");
        own(me, 9010, 0);
        own(me, 9011, 0);

        // 장바구니 구매 가능 합계 88000
        mvc.perform(get("/mypage/buy").header("Authorization", bearer(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.coupons[?(@.couponId == 9010)].applicable", contains(false)))
                .andExpect(jsonPath("$.result.coupons[?(@.couponId == 9010)].expectedDiscount", contains(0)))
                .andExpect(jsonPath("$.result.coupons[?(@.couponId == 9011)].applicable", contains(true)))
                .andExpect(jsonPath("$.result.coupons[?(@.couponId == 9011)].expectedDiscount", contains(1000)));

        mvc.perform(get("/mypage/buy/cpn?courseIds=12,7&couponId=9010").header("Authorization", bearer(me)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COUPON_NOT_APPLICABLE"))
                .andExpect(jsonPath("$.result.reason").value("MIN_AMOUNT_NOT_MET"))
                .andExpect(jsonPath("$.result.minAmount").value(90000));
        mvc.perform(get("/mypage/buy/cpn?courseIds=7&couponId=9011").header("Authorization", bearer(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.summary.couponDiscountAmount").value(1000))
                .andExpect(jsonPath("$.result.summary.finalAmount").value(38000));

        // 합계와 같으면 통과
        jdbc.update("UPDATE coupons SET min_amount = 88000 WHERE cid = 9010");
        mvc.perform(get("/mypage/buy/cpn?courseIds=12,7&couponId=9010").header("Authorization", bearer(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.summary.finalAmount").value(86000))
                .andExpect(jsonPath("$.result.minAmount").doesNotExist());
    }

    @Test @DisplayName("쿠폰 적용 409 CART_CHANGED: 장바구니에 없음·closed·이미 수강 중인 강좌")
    void couponCartChanged() throws Exception {
        cart(me, 15); // 담겨 있지만 이미 수강 중
        mvc.perform(get("/mypage/buy/cpn?courseIds=12,14,15,16,999&couponId=9001").header("Authorization", bearer(me)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CART_CHANGED"))
                .andExpect(jsonPath("$.message").value("주문할 수 없는 강의가 포함되어 있습니다."))
                .andExpect(jsonPath("$.result.unavailableCourseIds", contains(14, 15, 16, 999)));
    }

    @Test @DisplayName("쿠폰 적용 400 INVALID_INPUT: 누락·형식 오류·51개")
    void couponInvalid() throws Exception {
        mvc.perform(get("/mypage/buy/cpn").header("Authorization", bearer(me)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.result.errors[*].field", contains("courseIds", "couponId")));

        StringBuilder many = new StringBuilder("1");
        for (int i = 2; i <= 51; i++) many.append(',').append(i);
        for (String ids : new String[]{"12,x", "12,,7", "0", "-1", "1.5", many.toString()}) {
            mvc.perform(get("/mypage/buy/cpn").param("courseIds", ids).param("couponId", "9001").header("Authorization", bearer(me)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.result.errors[*].field", contains("courseIds")));
        }
        for (String cid : new String[]{"1.5", "abc", "0"}) {
            mvc.perform(get("/mypage/buy/cpn").param("courseIds", "7").param("couponId", cid).header("Authorization", bearer(me)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.result.errors[*].field", contains("couponId")));
        }
    }

    // ------------------------------------------------------------ GET /mypage/buy/pnt

    @Test @DisplayName("포인트 적용 200: 잔액 0이면 usePoint 0만 통과, 응답 모양(summary·point)은 그대로, DB 변경 없음")
    void pointApplied() throws Exception {
        mvc.perform(get("/mypage/buy/pnt?courseIds=12,7&couponId=9001&usePoint=0").header("Authorization", bearer(me)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("POINT_APPLIED"))
                .andExpect(jsonPath("$.message").value("포인트를 적용했습니다."))
                .andExpect(jsonPath("$.result.summary.productAmount").value(88000))
                .andExpect(jsonPath("$.result.summary.couponDiscountAmount").value(5000))
                .andExpect(jsonPath("$.result.summary.usePoint").value(0))
                .andExpect(jsonPath("$.result.summary.finalAmount").value(83000))
                .andExpect(jsonPath("$.result.point.balance").value(0))
                .andExpect(jsonPath("$.result.point.maxUsable").value(0));
        mvc.perform(get("/mypage/buy/pnt?courseIds=7&couponId=&usePoint=0").header("Authorization", bearer(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.summary.finalAmount").value(39000));
        assertEquals(5, unusedCoupons(me));
    }

    @Test @DisplayName("포인트 적용 400 INVALID_POINT: 잔액 0이면 1 이상은 EXCEEDS_BALANCE")
    void pointInvalid() throws Exception {
        mvc.perform(get("/mypage/buy/pnt?courseIds=12,7&usePoint=1").header("Authorization", bearer(me)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_POINT"))
                .andExpect(jsonPath("$.message").value("사용할 수 있는 포인트를 초과했습니다."))
                .andExpect(jsonPath("$.result.maxUsablePoint").value(0))
                .andExpect(jsonPath("$.result.balance").value(0))
                .andExpect(jsonPath("$.result.reason").value("EXCEEDS_BALANCE"));
    }

    @Test @DisplayName("포인트 적용 200/400: 잔액이 있으면 쿠폰 적용 후 금액까지 쓸 수 있고, 잔액·주문 금액을 넘으면 INVALID_POINT. 잔액은 그대로")
    void pointWithBalance() throws Exception {
        jdbc.update("UPDATE users SET point = 100000 WHERE user_id = ?", me);
        mvc.perform(get("/mypage/buy/pnt?courseIds=12,7&couponId=9001&usePoint=3000").header("Authorization", bearer(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("POINT_APPLIED"))
                .andExpect(jsonPath("$.result.summary.couponDiscountAmount").value(5000))
                .andExpect(jsonPath("$.result.summary.usePoint").value(3000))
                .andExpect(jsonPath("$.result.summary.finalAmount").value(80000))
                .andExpect(jsonPath("$.result.point.balance").value(100000))
                .andExpect(jsonPath("$.result.point.maxUsable").value(83000));
        mvc.perform(get("/mypage/buy/pnt?courseIds=12,7&couponId=9001&usePoint=83001").header("Authorization", bearer(me)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_POINT"))
                .andExpect(jsonPath("$.result.maxUsablePoint").value(83000))
                .andExpect(jsonPath("$.result.reason").value("EXCEEDS_ORDER_AMOUNT"));

        jdbc.update("UPDATE users SET point = 2000 WHERE user_id = ?", me);
        mvc.perform(get("/mypage/buy").header("Authorization", bearer(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.point.balance").value(2000))
                .andExpect(jsonPath("$.result.point.maxUsable").value(2000));
        mvc.perform(get("/mypage/buy/pnt?courseIds=12,7&usePoint=2001").header("Authorization", bearer(me)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.result.maxUsablePoint").value(2000))
                .andExpect(jsonPath("$.result.balance").value(2000))
                .andExpect(jsonPath("$.result.reason").value("EXCEEDS_BALANCE"));
        assertEquals(2000L, jdbc.queryForObject("SELECT point FROM users WHERE user_id = ?", Long.class, me));
    }

    @Test @DisplayName("포인트 적용 400/409/401: 입력 오류, 쿠폰 불가, 장바구니 변경, 토큰 없음이 먼저")
    void pointErrors() throws Exception {
        mvc.perform(get("/mypage/buy/pnt").header("Authorization", bearer(me)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.result.errors[*].field", contains("courseIds", "usePoint")));
        for (String q : new String[]{"courseIds=7&usePoint=-1", "courseIds=7&usePoint=1.5", "courseIds=7&usePoint=x"}) {
            mvc.perform(get("/mypage/buy/pnt?" + q).header("Authorization", bearer(me)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.result.errors[*].field", contains("usePoint")));
        }
        mvc.perform(get("/mypage/buy/pnt?courseIds=7&couponId=x&usePoint=0").header("Authorization", bearer(me)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.result.errors[*].field", contains("couponId")));
        mvc.perform(get("/mypage/buy/pnt?courseIds=7&couponId=9004&usePoint=0").header("Authorization", bearer(me)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COUPON_NOT_APPLICABLE"))
                .andExpect(jsonPath("$.result.reason").value("ALREADY_USED"));
        mvc.perform(get("/mypage/buy/pnt?courseIds=7,16&usePoint=0").header("Authorization", bearer(me)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.result.unavailableCourseIds", contains(16)));
        mvc.perform(get("/mypage/buy/pnt"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    // ------------------------------------------------------------ POST /mypage/buy

    @Test @DisplayName("주문 생성 201 ORDER_CREATED: pending 주문 + 항목 스냅샷, 쿠폰·장바구니는 그대로, 본문 amount 무시")
    void orderCreated() throws Exception {
        String res = mvc.perform(post("/mypage/buy").header("Authorization", bearer(me)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"courseIds\":[12,7],\"couponId\":9001,\"usePoint\":0,\"payMethod\":\"CARD\",\"amount\":1}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("ORDER_CREATED"))
                .andExpect(jsonPath("$.message").value("주문이 생성되었습니다. 결제를 진행해주세요."))
                .andExpect(jsonPath("$.result.orderId").isNumber())
                .andExpect(jsonPath("$.result.orderName").value("웹 보안 기초 외 1개"))
                .andExpect(jsonPath("$.result.amount").value(83000))
                .andExpect(jsonPath("$.result.pay").value("CARD"))
                .andExpect(jsonPath("$.result.summary.productAmount").value(88000))
                .andExpect(jsonPath("$.result.summary.couponDiscountAmount").value(5000))
                .andExpect(jsonPath("$.result.summary.usePoint").value(0))
                .andExpect(jsonPath("$.result.summary.finalAmount").value(83000))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        long oid = Long.parseLong(res.replaceAll(".*\"orderId\":(\\d+).*", "$1"));

        Map<String, Object> order = jdbc.queryForMap("SELECT * FROM orders WHERE oid = ?", oid);
        assertEquals(me, ((Number) order.get("USER_ID")).longValue());
        assertEquals(9001L, ((Number) order.get("CID")).longValue());
        assertEquals(5000L, ((Number) order.get("DISCOUNT")).longValue());
        assertEquals(83000L, ((Number) order.get("APRICE")).longValue());
        assertEquals("CARD", order.get("PAY"));
        assertEquals("pending", order.get("STATUS"));
        assertTrue(((String) order.get("ONO")).matches("ORD-\\d{8}-" + oid));
        assertNull(order.get("PAID_AT"));
        assertEquals(0L, ((Number) order.get("USE_POINT")).longValue());
        // Aprice = SUM(order_items.price) - discount - use_point
        assertEquals(0L, jdbc.queryForObject(
                "SELECT SUM(oi.price) - o.discount - o.use_point - o.Aprice FROM orders o JOIN order_items oi ON oi.oid = o.oid"
                        + " WHERE o.oid = ? GROUP BY o.discount, o.use_point, o.Aprice", Long.class, oid));
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM order_items WHERE oid = ? AND Lid = 12 AND Lname = '웹 보안 기초' AND price = 49000", Long.class, oid));

        assertEquals(5, unusedCoupons(me));
        assertEquals(3L, jdbc.queryForObject("SELECT COUNT(*) FROM cart WHERE user_id = ?", Long.class, me));
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM enrollments WHERE user_id = ?", Long.class, me));
    }

    @Test @DisplayName("주문 생성 201: usePoint를 orders.use_point에 저장하고 Aprice에서 뺀다. pending이면 잔액은 아직 그대로")
    void orderCreatedWithPoints() throws Exception {
        jdbc.update("UPDATE users SET point = 10000 WHERE user_id = ?", me);
        String res = mvc.perform(post("/mypage/buy").header("Authorization", bearer(me)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"courseIds\":[12,7],\"couponId\":9001,\"usePoint\":3000,\"payMethod\":\"CARD\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("ORDER_CREATED"))
                .andExpect(jsonPath("$.result.amount").value(80000))
                .andExpect(jsonPath("$.result.summary.usePoint").value(3000))
                .andExpect(jsonPath("$.result.summary.finalAmount").value(80000))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        long oid = Long.parseLong(res.replaceAll(".*\"orderId\":(\\d+).*", "$1"));
        Map<String, Object> order = jdbc.queryForMap("SELECT * FROM orders WHERE oid = ?", oid);
        assertEquals(5000L, ((Number) order.get("DISCOUNT")).longValue());
        assertEquals(3000L, ((Number) order.get("USE_POINT")).longValue());
        assertEquals(80000L, ((Number) order.get("APRICE")).longValue());
        assertEquals(10000L, jdbc.queryForObject("SELECT point FROM users WHERE user_id = ?", Long.class, me));
    }

    @Test @DisplayName("주문 생성 201 ORDER_COMPLETED: 포인트로 0원이 되면 바로 paid, 잔액 차감, use_point 저장")
    void orderCompletedByPoints() throws Exception {
        jdbc.update("UPDATE users SET point = 50000 WHERE user_id = ?", me);
        mvc.perform(post("/mypage/buy").header("Authorization", bearer(me)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"courseIds\":[7],\"usePoint\":39000,\"payMethod\":\"CARD\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("ORDER_COMPLETED"))
                .andExpect(jsonPath("$.result.summary.usePoint").value(39000))
                .andExpect(jsonPath("$.result.summary.finalAmount").value(0));
        Map<String, Object> order = jdbc.queryForMap("SELECT * FROM orders WHERE user_id = ?", me);
        assertEquals("paid", order.get("STATUS"));
        assertEquals(39000L, ((Number) order.get("USE_POINT")).longValue());
        assertEquals(0L, ((Number) order.get("APRICE")).longValue());
        assertEquals(11000L, jdbc.queryForObject("SELECT point FROM users WHERE user_id = ?", Long.class, me));
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM enrollments WHERE user_id = ? AND Lid = 7", Long.class, me));
    }

    @Test @DisplayName("주문 생성 201 ORDER_COMPLETED: [의도된 취약점 A04] 가격 초과 쿠폰(clamp 제거)으로 0원 무료 수강")
    void orderCompletedFree() throws Exception {
        // 7+12=88000, 강의 7 전용 쿠폰(할인 88000, un-clamp) → 88000 할인 → 0원 → PG 없이 바로 완료(무료 수강)
        mvc.perform(post("/mypage/buy").header("Authorization", bearer(me)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"courseIds\":[7,12],\"couponId\":9008,\"usePoint\":0,\"payMethod\":\"TRANSFER\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("ORDER_COMPLETED"))
                .andExpect(jsonPath("$.message").value("결제가 완료되었습니다."))
                .andExpect(jsonPath("$.result.summary.finalAmount").value(0))
                .andExpect(jsonPath("$.result.summary.usePoint").value(0))
                .andExpect(jsonPath("$.result.courseIds", contains(7, 12)))
                .andExpect(jsonPath("$.result.length()").value(3));
        Map<String, Object> order = jdbc.queryForMap("SELECT * FROM orders WHERE user_id = ?", me);
        assertEquals("paid", order.get("STATUS"));
        assertEquals(0L, ((Number) order.get("APRICE")).longValue());
        assertNull(order.get("PAY"));
        assertNotNull(order.get("PAID_AT"));
        assertEquals(1L, jdbc.queryForObject("SELECT used FROM user_coupons WHERE user_id = ? AND cid = 9008", Long.class, me));
        assertEquals(2L, jdbc.queryForObject("SELECT COUNT(*) FROM enrollments WHERE user_id = ? AND Lid IN (7, 12)", Long.class, me));
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM cart WHERE user_id = ? AND Lid IN (7, 12)", Long.class, me));
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM cart WHERE user_id = ?", Long.class, me));
    }

    @Test @DisplayName("주문 생성 400/409: 입력 오류, 쿠폰 불가, 포인트 초과, 장바구니 변경 — 주문을 만들지 않는다")
    void orderRejected() throws Exception {
        mvc.perform(post("/mypage/buy").header("Authorization", bearer(me)).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.result.errors[*].field", contains("courseIds", "payMethod")));
        mvc.perform(post("/mypage/buy").header("Authorization", bearer(me)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"courseIds\":[7,\"x\"],\"couponId\":1.5,\"usePoint\":-1,\"payMethod\":\"CASH\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.result.errors[*].field", contains("courseIds", "couponId", "usePoint", "payMethod")));
        mvc.perform(post("/mypage/buy").header("Authorization", bearer(me)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"courseIds\":[7],\"couponId\":9002,\"payMethod\":\"CARD\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COUPON_NOT_APPLICABLE"))
                .andExpect(jsonPath("$.result.reason").value("TARGET_COURSE_NOT_IN_ORDER"));
        mvc.perform(post("/mypage/buy").header("Authorization", bearer(me)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"courseIds\":[7],\"usePoint\":5000,\"payMethod\":\"CARD\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_POINT"))
                .andExpect(jsonPath("$.result.maxUsablePoint").value(0))
                .andExpect(jsonPath("$.result.reason").value("EXCEEDS_BALANCE"));
        mvc.perform(post("/mypage/buy").header("Authorization", bearer(me)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"courseIds\":[7,14],\"payMethod\":\"CARD\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CART_CHANGED"))
                .andExpect(jsonPath("$.result.unavailableCourseIds", contains(14)));
        mvc.perform(post("/mypage/buy").header("Authorization", bearer(me)).contentType(MediaType.APPLICATION_JSON).content("{broken"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.result.errors[*].field", contains("body")));
        mvc.perform(post("/mypage/buy").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM orders", Long.class));
    }

    @Test @DisplayName("쿠폰 적용 401: 토큰 없음이 입력 오류보다 먼저, 만료 TOKEN_EXPIRED")
    void couponUnauthorized() throws Exception {
        mvc.perform(get("/mypage/buy/cpn"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mvc.perform(get("/mypage/buy/cpn?courseIds=7&couponId=9001").header("Authorization", "Bearer " + token(me, 60, SECRET)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TOKEN_EXPIRED"));
    }

    // ------------------------------------------------------------ 바로 구매 (direct=true, 장바구니 없이)

    @Test @DisplayName("바로 구매: 장바구니에 없는 강좌도 direct=true면 조회·쿠폰·주문 가능, 장바구니는 그대로")
    void directBuy() throws Exception {
        // 16은 open이고 내 장바구니에 없음
        mvc.perform(get("/mypage/buy?courseIds=16&direct=true").header("Authorization", bearer(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.items", hasSize(1)))
                .andExpect(jsonPath("$.result.items[0].courseId").value(16))
                .andExpect(jsonPath("$.result.items[0].purchasable").value(true))
                .andExpect(jsonPath("$.result.summary.productAmount").value(25000));
        // 이미 수강 중인 15는 purchasable false
        mvc.perform(get("/mypage/buy?courseIds=15&direct=true").header("Authorization", bearer(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.items[0].purchasable").value(false));
        mvc.perform(get("/mypage/buy?direct=true").header("Authorization", bearer(me)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.result.errors[0].field").value("courseIds"));
        mvc.perform(get("/mypage/buy/cpn?courseIds=16&couponId=9001&direct=true").header("Authorization", bearer(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.summary.finalAmount").value(20000));
        // direct 없이는 기존처럼 장바구니에 없어서 409
        mvc.perform(get("/mypage/buy/cpn?courseIds=16&couponId=9001").header("Authorization", bearer(me)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CART_CHANGED"));
        mvc.perform(post("/mypage/buy").header("Authorization", bearer(me)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"courseIds\":[16],\"payMethod\":\"CARD\",\"direct\":true}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("ORDER_CREATED"))
                .andExpect(jsonPath("$.result.amount").value(25000));
        // 바로 구매해도 장바구니(7, 12, 14)는 바뀌지 않음
        assertEquals(3L, jdbc.queryForObject("SELECT COUNT(*) FROM cart WHERE user_id = ?", Long.class, me));
        // 이미 수강 중인 강좌는 바로 구매도 409
        mvc.perform(post("/mypage/buy").header("Authorization", bearer(me)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"courseIds\":[15],\"payMethod\":\"CARD\",\"direct\":true}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CART_CHANGED"));
    }
}
