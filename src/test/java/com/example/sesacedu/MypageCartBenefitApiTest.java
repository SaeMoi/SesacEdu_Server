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
import java.util.Objects;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * mypage 패키지의 cart / cart_add / cart_del / bnf / cpn이 Notion 명세(장바구니, 장바구니 추가·삭제, 나의해택, 쿠폰등록)대로
 * 응답하는지 확인. H2(Oracle 모드, application-test.yml + schema-test.sql)로 실행:
 *   gradlew test --tests com.example.sesacedu.MypageCartBenefitApiTest
 */
@SuppressWarnings("null") // VS Code(JDT) null 분석이 Spring/MockMvc API에 내는 경고 제외. 실행에는 영향 없음
@SpringBootTest(properties = "JWT_SECRET=" + MypageCartBenefitApiTest.SECRET)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MypageCartBenefitApiTest {

    static final String SECRET = "mypage-cart-benefit-test-secret-at-least-32-bytes!!";
    static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    long me;
    long other;
    long empty;

    @BeforeEach
    void setUp() {
        for (String t : new String[]{"user_coupons", "coupons", "cart", "enrollments", "lectures", "users"}) {
            jdbc.update("DELETE FROM " + t);
        }
        jdbc.update("INSERT INTO users (mail, pw, uname, nickname, auth) VALUES ('me@example.test', 'x', '김새싹', '새싹', 1)");
        jdbc.update("INSERT INTO users (mail, pw, uname, nickname, auth) VALUES ('other@example.test', 'x', '이타인', '타인', 1)");
        jdbc.update("INSERT INTO users (mail, pw, uname, nickname, auth) VALUES ('empty@example.test', 'x', '빈사람', '빈', 0)");
        me = id("me@example.test");
        other = id("other@example.test");
        empty = id("empty@example.test");

        lecture(7, "리눅스 입문", 39000, "open");
        lecture(12, "웹 보안 기초", 49000, "open");
        lecture(13, "준비 중 강의", 10000, "draft");
        lecture(14, "내린 강의", 20000, "closed");
        lecture(15, "산 강의", 30000, "open");
        lecture(16, "새 강의", 25000, "open");

        cart(me, 7);
        cart(me, 12);
        cart(me, 14);    // 담은 뒤 closed 됨 → purchasable false, 합계에는 포함
        cart(other, 16);
        jdbc.update("INSERT INTO enrollments (user_id, Lid, done) VALUES (?, 15, 0)", me);

        // 쿠폰: 9001 전체·여유, 9002 강의 12 전용, 9003 만료, 9004 사용함, 9005 남의 것, 9006 미등록·마지막 1장, 9007 수량 미확정
        coupon(9001, null, "첫 학습 응원 쿠폰", "WELCOME2026A", 5000, 100, 1, "2099-10-31 23:59:59");
        coupon(9002, 12L, "웹 보안 기초 쿠폰", "WEBSEC2026AB", 10000, 10, 1, "2099-10-20 23:59:59");
        coupon(9003, null, "지난 쿠폰", "EXPIRED2025A", 3000, 10, 1, "2020-01-01 00:00:00");
        coupon(9004, null, "써 버린 쿠폰", "USEDCOUPON01", 2000, 10, 1, "2099-12-31 00:00:00");
        coupon(9005, null, "남의 쿠폰", "OTHERCOUPON1", 1000, 10, 1, "2099-01-01 00:00:00");
        coupon(9006, 7L, "가을 맞이 쿠폰", "AUTUMN2026AB", 7000, 3, 2, "2099-11-30 23:59:59");
        coupon(9007, null, "무제한 쿠폰", "UNLIMITED26A", 1000, null, 0, "2099-11-30 23:59:59");
        own(me, 9001, 0);
        own(me, 9002, 0);
        own(me, 9003, 0);
        own(me, 9004, 1);
        own(other, 9005, 0);
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

    void coupon(long cid, Long lid, String name, String code, int discount, Integer quantity, int issued, String due) {
        jdbc.update("INSERT INTO coupons (cid, user_id, Lid, cname, code, discount, quantity, issued, due) VALUES (?, 9001, ?, ?, ?, ?, ?, ?, ?)",
                cid, lid, name, code, discount, quantity, issued, Timestamp.valueOf(due));
    }

    void own(long uid, long cid, int used) {
        jdbc.update("INSERT INTO user_coupons (user_id, cid, used) VALUES (?, ?, ?)", uid, cid, used);
    }

    long cartCount(long uid) {
        return Objects.requireNonNull(jdbc.queryForObject("SELECT COUNT(*) FROM cart WHERE user_id = ?", Long.class, uid));
    }

    long issued(long cid) {
        return Objects.requireNonNull(jdbc.queryForObject("SELECT issued FROM coupons WHERE cid = ?", Long.class, cid));
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

    // ------------------------------------------------------------ GET /mypage/cart

    @Test @DisplayName("장바구니 200: Lid 내림차순, 현재 Lprice, open 아니면 purchasable false, 합계, 남의 항목 제외")
    void cartList() throws Exception {
        mvc.perform(get("/mypage/cart").header("Authorization", bearer(me)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("CART_FOUND"))
                .andExpect(jsonPath("$.message").value("장바구니를 조회했습니다."))
                .andExpect(jsonPath("$.result.items[*].courseId", contains(14, 12, 7)))
                .andExpect(jsonPath("$.result.items[1].title").value("웹 보안 기초"))
                .andExpect(jsonPath("$.result.items[1].price").value(49000))
                .andExpect(jsonPath("$.result.items[1].purchasable").value(true))
                .andExpect(jsonPath("$.result.items[1].length()").value(4))
                .andExpect(jsonPath("$.result.items[0].purchasable").value(false))
                .andExpect(jsonPath("$.result.summary.itemCount").value(3))
                .andExpect(jsonPath("$.result.summary.productAmount").value(108000));
    }

    @Test @DisplayName("장바구니 200: 비어 있으면 items: [], 합계 0")
    void cartEmpty() throws Exception {
        mvc.perform(get("/mypage/cart").header("Authorization", bearer(empty)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.items", hasSize(0)))
                .andExpect(jsonPath("$.result.summary.itemCount").value(0))
                .andExpect(jsonPath("$.result.summary.productAmount").value(0));
    }

    @Test @DisplayName("장바구니 401: 토큰 없음 UNAUTHORIZED, 만료 TOKEN_EXPIRED")
    void cartUnauthorized() throws Exception {
        mvc.perform(get("/mypage/cart"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.result").value(nullValue()));
        mvc.perform(get("/mypage/cart").header("Authorization", "Bearer " + token(me, 60, SECRET)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TOKEN_EXPIRED"));
    }

    // ------------------------------------------------------------ POST /mypage/cart/add

    @Test @DisplayName("장바구니 추가 201: courseId·totalCount, 본문의 price는 무시")
    void cartAdd() throws Exception {
        mvc.perform(post("/mypage/cart/add").header("Authorization", bearer(me))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"courseId\":16,\"price\":1}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("CART_ITEM_ADDED"))
                .andExpect(jsonPath("$.message").value("장바구니에 담았습니다."))
                .andExpect(jsonPath("$.result.courseId").value(16))
                .andExpect(jsonPath("$.result.totalCount").value(4));
        assertEquals(4, cartCount(me));
        assertEquals(1, cartCount(other));
    }

    @Test @DisplayName("장바구니 추가 404: 없는 강좌·draft·closed는 모두 COURSE_NOT_FOUND")
    void cartAddNotFound() throws Exception {
        for (long lid : new long[]{999, 13, 14}) {
            mvc.perform(post("/mypage/cart/add").header("Authorization", bearer(empty))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"courseId\":" + lid + "}"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("COURSE_NOT_FOUND"))
                    .andExpect(jsonPath("$.result").value(nullValue()));
        }
        assertEquals(0, cartCount(empty));
    }

    @Test @DisplayName("장바구니 추가 409: ALREADY_IN_CART, ALREADY_ENROLLED, CART_FULL(maxCount 50)")
    void cartAddConflict() throws Exception {
        mvc.perform(post("/mypage/cart/add").header("Authorization", bearer(me))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"courseId\":12}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_IN_CART"));
        mvc.perform(post("/mypage/cart/add").header("Authorization", bearer(me))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"courseId\":15}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_ENROLLED"));
        assertEquals(3, cartCount(me));

        for (long lid = 1000; lid < 1050; lid++) cart(empty, lid);
        mvc.perform(post("/mypage/cart/add").header("Authorization", bearer(empty))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"courseId\":16}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CART_FULL"))
                .andExpect(jsonPath("$.result.maxCount").value(50));
    }

    @Test @DisplayName("장바구니 추가 400: courseId 누락·문자열·음수·소수, 깨진 JSON")
    void cartAddInvalid() throws Exception {
        for (String json : new String[]{"{}", "{\"courseId\":\"12\"}", "{\"courseId\":-1}", "{\"courseId\":1.5}"}) {
            mvc.perform(post("/mypage/cart/add").header("Authorization", bearer(me))
                            .contentType(MediaType.APPLICATION_JSON).content(json))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                    .andExpect(jsonPath("$.result.errors[*].field", contains("courseId")));
        }
        mvc.perform(post("/mypage/cart/add").header("Authorization", bearer(me))
                        .contentType(MediaType.APPLICATION_JSON).content("{broken"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.result.errors[*].field", contains("body")));
    }

    @Test @DisplayName("장바구니 추가 401: 토큰 없으면 입력 오류보다 먼저")
    void cartAddUnauthorized() throws Exception {
        mvc.perform(post("/mypage/cart/add").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mvc.perform(post("/mypage/cart/add").contentType(MediaType.APPLICATION_JSON).content("{broken"))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------ DELETE /mypage/cart/del

    @Test @DisplayName("장바구니 삭제 200: 내 항목만 지우고, 없는·남의 courseId는 무시, 중복 제거")
    void cartDelete() throws Exception {
        mvc.perform(delete("/mypage/cart/del").header("Authorization", bearer(me))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"courseIds\":[12,7,12,16,999]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("CART_ITEMS_DELETED"))
                .andExpect(jsonPath("$.message").value("선택한 강의를 장바구니에서 삭제했습니다."))
                .andExpect(jsonPath("$.result.deletedCount").value(2))
                .andExpect(jsonPath("$.result.totalCount").value(1));
        assertEquals(1, cartCount(other)); // 남의 16은 그대로

        // 이미 지운 것을 다시 지워도 200
        mvc.perform(delete("/mypage/cart/del").header("Authorization", bearer(me))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"courseIds\":[12]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.deletedCount").value(0))
                .andExpect(jsonPath("$.result.totalCount").value(1));
    }

    @Test @DisplayName("장바구니 삭제 400: 누락·빈 배열·배열 아님·잘못된 원소·51개")
    void cartDeleteInvalid() throws Exception {
        StringBuilder many = new StringBuilder("{\"courseIds\":[1");
        for (int i = 2; i <= 51; i++) many.append(',').append(i);
        many.append("]}");
        for (String json : new String[]{"{}", "{\"courseIds\":[]}", "{\"courseIds\":12}", "{\"courseIds\":[12,\"x\"]}",
                "{\"courseIds\":[0]}", many.toString()}) {
            mvc.perform(delete("/mypage/cart/del").header("Authorization", bearer(me))
                            .contentType(MediaType.APPLICATION_JSON).content(json))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                    .andExpect(jsonPath("$.message").value("삭제할 강의를 선택해주세요."))
                    .andExpect(jsonPath("$.result.errors[0].field").value("courseIds"))
                    .andExpect(jsonPath("$.result.errors[0].reason").value("1개 이상 50개 이하로 선택해야 합니다."));
        }
        assertEquals(3, cartCount(me));
    }

    @Test @DisplayName("장바구니 삭제 401: 토큰 없음·만료")
    void cartDeleteUnauthorized() throws Exception {
        mvc.perform(delete("/mypage/cart/del").contentType(MediaType.APPLICATION_JSON).content("{\"courseIds\":[12]}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mvc.perform(delete("/mypage/cart/del").header("Authorization", "Bearer " + token(me, 60, SECRET))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"courseIds\":[12]}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TOKEN_EXPIRED"));
        assertEquals(3, cartCount(me));
    }

    // ------------------------------------------------------------ GET /mypage/bnf

    @Test @DisplayName("나의해택 200: 미사용·미만료만 만료 임박순, targetCourse, 건수, pointBalance 0 (포인트 보류)")
    void benefits() throws Exception {
        mvc.perform(get("/mypage/bnf").header("Authorization", bearer(me)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("BENEFITS_FOUND"))
                .andExpect(jsonPath("$.message").value("보유 혜택을 조회했습니다."))
                .andExpect(jsonPath("$.result.summary.availableCouponCount").value(2))
                .andExpect(jsonPath("$.result.summary.pointBalance").value(0))
                .andExpect(jsonPath("$.result.coupons[*].couponId", contains(9002, 9001)))
                .andExpect(jsonPath("$.result.coupons[0].name").value("웹 보안 기초 쿠폰"))
                .andExpect(jsonPath("$.result.coupons[0].discountValue").value(10000))
                .andExpect(jsonPath("$.result.coupons[0].targetCourse.courseId").value(12))
                .andExpect(jsonPath("$.result.coupons[0].targetCourse.title").value("웹 보안 기초"))
                .andExpect(jsonPath("$.result.coupons[0].expiresAt").value(kst("2099-10-20 23:59:59")))
                .andExpect(jsonPath("$.result.coupons[0].length()").value(5))
                .andExpect(jsonPath("$.result.coupons[0].code").doesNotExist())
                .andExpect(jsonPath("$.result.coupons[1].targetCourse").value(nullValue()))
                .andExpect(jsonPath("$.result.page.totalElements").value(2))
                .andExpect(jsonPath("$.result.page.hasNext").value(false));
    }

    @Test @DisplayName("나의해택 200: 페이징, 쿠폰 없으면 coupons: []")
    void benefitsPagingAndEmpty() throws Exception {
        mvc.perform(get("/mypage/bnf?page=0&size=1").header("Authorization", bearer(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.coupons[*].couponId", contains(9002)))
                .andExpect(jsonPath("$.result.page.totalPages").value(2))
                .andExpect(jsonPath("$.result.page.hasNext").value(true))
                .andExpect(jsonPath("$.result.summary.availableCouponCount").value(2));
        mvc.perform(get("/mypage/bnf").header("Authorization", bearer(empty)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.coupons", hasSize(0)))
                .andExpect(jsonPath("$.result.summary.availableCouponCount").value(0))
                .andExpect(jsonPath("$.result.summary.pointBalance").value(0));
    }

    @Test @DisplayName("나의해택 400: page·size 범위 오류, 401: 토큰 없음이 먼저")
    void benefitsInvalid() throws Exception {
        mvc.perform(get("/mypage/bnf?page=-1&size=51").header("Authorization", bearer(me)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.result.errors[*].field", contains("page", "size")))
                .andExpect(jsonPath("$.result.errors[1].reason").value("1 이상 50 이하여야 합니다."));
        mvc.perform(get("/mypage/bnf?size=0"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    // ------------------------------------------------------------ POST /mypage/cpn

    @Test @DisplayName("쿠폰등록 201: 하이픈·소문자 정규화, 나의해택 항목 형식, issued 1 증가")
    void couponRegister() throws Exception {
        mvc.perform(post("/mypage/cpn").header("Authorization", bearer(me))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"couponCode\":\"autu-mn20-26ab\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("COUPON_REGISTERED"))
                .andExpect(jsonPath("$.message").value("쿠폰이 등록되었습니다."))
                .andExpect(jsonPath("$.result.couponId").value(9006))
                .andExpect(jsonPath("$.result.name").value("가을 맞이 쿠폰"))
                .andExpect(jsonPath("$.result.discountValue").value(7000))
                .andExpect(jsonPath("$.result.targetCourse.courseId").value(7))
                .andExpect(jsonPath("$.result.expiresAt").value(kst("2099-11-30 23:59:59")))
                .andExpect(jsonPath("$.result.length()").value(5));
        assertEquals(3, issued(9006));
        Long received = jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_coupons WHERE user_id = ? AND cid = 9006 AND used = 0 AND received_at IS NOT NULL", Long.class, me);
        assertEquals(1L, received);

        // 수량 미확정(quantity NULL)은 제한 없이 발급
        mvc.perform(post("/mypage/cpn").header("Authorization", bearer(me))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"couponCode\":\"UNLIMITED26A\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.result.couponId").value(9007));
    }

    @Test @DisplayName("쿠폰등록 409: 이미 등록(수량 그대로), 소진(user_coupons 롤백)")
    void couponConflict() throws Exception {
        mvc.perform(post("/mypage/cpn").header("Authorization", bearer(me))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"couponCode\":\"WELCOME2026A\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COUPON_ALREADY_REGISTERED"))
                .andExpect(jsonPath("$.result").value(nullValue()));
        assertEquals(1, issued(9001));

        jdbc.update("UPDATE coupons SET issued = quantity WHERE cid = 9006");
        mvc.perform(post("/mypage/cpn").header("Authorization", bearer(me))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"couponCode\":\"AUTUMN2026AB\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COUPON_SOLD_OUT"))
                .andExpect(jsonPath("$.message").value("쿠폰이 모두 소진되었습니다."));
        assertEquals(3, issued(9006));
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM user_coupons WHERE user_id = ? AND cid = 9006", Long.class, me));
    }

    @Test @DisplayName("쿠폰등록 400: 없는 코드·만료 코드는 INVALID_COUPON_CODE, 형식 오류는 INVALID_INPUT")
    void couponInvalid() throws Exception {
        for (String code : new String[]{"NOSUCHCODE12", "EXPIRED2025A"}) {
            mvc.perform(post("/mypage/cpn").header("Authorization", bearer(empty))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"couponCode\":\"" + code + "\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_COUPON_CODE"))
                    .andExpect(jsonPath("$.message").value("유효하지 않은 쿠폰 코드입니다."));
        }
        for (String json : new String[]{"{}", "{\"couponCode\":\"SHORT\"}", "{\"couponCode\":\"WELCOME2026A!\"}",
                "{\"couponCode\":\"ABCDEFGHIJKLMNOPQ\"}"}) {
            mvc.perform(post("/mypage/cpn").header("Authorization", bearer(me))
                            .contentType(MediaType.APPLICATION_JSON).content(json))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                    .andExpect(jsonPath("$.result.errors[*].field", contains("couponCode")));
        }
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM user_coupons WHERE user_id = ?", Long.class, empty));
    }

    @Test @DisplayName("쿠폰등록 401: 토큰 없음이 입력 오류보다 먼저, 만료 TOKEN_EXPIRED")
    void couponUnauthorized() throws Exception {
        mvc.perform(post("/mypage/cpn").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mvc.perform(post("/mypage/cpn").header("Authorization", "Bearer " + token(me, 60, SECRET))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"couponCode\":\"AUTUMN2026AB\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TOKEN_EXPIRED"));
        assertEquals(2, issued(9006));
    }
}
