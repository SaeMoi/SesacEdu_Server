package com.example.sesacedu;

import com.jayway.jsonpath.JsonPath;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.util.DigestUtils;
import org.springframework.util.FileSystemUtils;

import javax.crypto.SecretKey;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * JWT 흐름 테스트: 실제 POST /login이 준 토큰으로 시작해서 재발급(POST /login/replc)을 이어 가고,
 * 보호된 API 전부가 Authorization 헤더 문제(없음·빈 값·Bearer 아님·형식 오류·위조·만료)에 401을 주는지 확인한다.
 *
 * 만료는 기다리지 않는다: expire()가 서버가 준 토큰의 클레임(sub·role·reissue_num)을 그대로 두고
 * exp만 과거로 옮겨 같은 키로 다시 서명한다 (서버 시계를 N초 앞으로 돌린 것과 같은 효과).
 * 서명 키는 HS512 위조 토큰도 만들 수 있게 64바이트 이상으로 둔다.
 *   gradlew test --tests com.example.sesacedu.JwtFlowTest
 */
@SuppressWarnings("null") // VS Code(JDT) null 분석이 Spring/MockMvc API에 내는 경고 제외. 실행에는 영향 없음
@SpringBootTest(properties = {
        "JWT_SECRET=" + JwtFlowTest.SECRET,
        "app.upload.profile-dir=build/test-jwt-images"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class JwtFlowTest {

    static final String SECRET = "jwt-flow-test-secret-long-enough-for-hs512-signatures-0123456789abcdef";
    static final SecretKey KEY = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
    static final String PW = "Passw0rd!";
    static final String EMAIL = "jwt@example.test";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    long uid;

    @BeforeEach
    void setUp() {
        FileSystemUtils.deleteRecursively(Paths.get("build/test-jwt-images").toFile());
        for (String t : new String[]{"order_items", "orders", "user_coupons", "coupons", "cart", "enrollments", "lectures", "users"}) {
            jdbc.update("DELETE FROM " + t);
        }
        jdbc.update("INSERT INTO users (mail, pw, uname, nickname, auth) VALUES (?, ?, '김토큰', '토큰', 1)",
                EMAIL, DigestUtils.md5DigestAsHex(PW.getBytes(StandardCharsets.UTF_8)));
        uid = jdbc.queryForObject("SELECT user_id FROM users WHERE mail = ?", Long.class, EMAIL);
        jdbc.update("INSERT INTO lectures (Lid, user_id, name, category, Lprice, status) VALUES (7, 9001, '리눅스 입문', '웹 보안', 39000, 'open')");
        jdbc.update("INSERT INTO coupons (cid, user_id, cname, code, discount, due) VALUES (9201, 9001, '전체 쿠폰', 'WELCOME2026ABCD', 1000, ?)",
                Timestamp.valueOf("2099-12-31 23:59:59"));
    }

    // ------------------------------------------------------------ 토큰 헬퍼

    /** POST /login 성공 → 응답 헤더 "Bearer xxx" 그대로 */
    String login() throws Exception {
        String h = mvc.perform(post("/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + EMAIL + "\",\"pw\":\"" + PW + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getHeader("Authorization");
        assertNotNull(h);
        return h;
    }

    static Claims claims(String bearer) {
        return Jwts.parser().verifyWith(KEY).build().parseSignedClaims(bearer.substring("Bearer ".length())).getPayload();
    }

    static int reissueNum(String bearer) {
        return ((Number) claims(bearer).get("reissue_num")).intValue();
    }

    /** 서버가 준 (아직 유효한) 토큰을 secondsAgo초 전에 만료된 것으로: 클레임은 그대로, exp만 옮겨 같은 키로 서명 */
    static String expire(String bearer, long secondsAgo) {
        Claims c = claims(bearer);
        Map<String, Object> copy = new LinkedHashMap<>(c);
        copy.remove("exp");
        return "Bearer " + Jwts.builder().claims(copy)
                .expiration(new Date(System.currentTimeMillis() - secondsAgo * 1000))
                .signWith(KEY, Jwts.SIG.HS256).compact();
    }

    ResultActions reissue(String bearer) throws Exception {
        return mvc.perform(post("/login/replc").header("Authorization", bearer));
    }

    String reissued(String expiredBearer, int expectNum) throws Exception {
        MvcResult r = reissue(expiredBearer).andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("REISSUE_SUCCESS"))
                .andExpect(jsonPath("$.result.reissueNum").value(expectNum)).andReturn();
        String h = r.getResponse().getHeader("Authorization");
        assertNotNull(h);
        assertEquals(expectNum, reissueNum(h));
        return h;
    }

    ResultActions mypage(String bearer) throws Exception {
        return mvc.perform(get("/mypage").header("Authorization", bearer));
    }

    // ------------------------------------------------------------ 재발급

    @Test @DisplayName("재발급 사슬: 로그인(2) → 아직 유효하면 400 → 만료 시 /mypage 401 TOKEN_EXPIRED → 재발급 1 → 0 → REISSUE_LIMIT → 다시 로그인하면 2")
    void reissueChainFromRealLogin() throws Exception {
        String t0 = login();
        assertEquals(2, reissueNum(t0));
        mypage(t0).andExpect(status().isOk());
        reissue(t0).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("TOKEN_NOT_EXPIRED"));

        String e0 = expire(t0, 60);
        mypage(e0).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("TOKEN_EXPIRED"));

        String t1 = reissued(e0, 1);
        assertEquals(String.valueOf(uid), claims(t1).getSubject());
        assertEquals("STUDENT", claims(t1).get("role"));
        assertTrue(claims(t1).getExpiration().getTime() - System.currentTimeMillis() > 3500_000, "새 토큰은 60분");
        assertEquals(t1.substring(7), jdbc.queryForObject("SELECT token FROM users WHERE user_id = ?", String.class, uid));
        mypage(t1).andExpect(status().isOk());

        String t2 = reissued(expire(t1, 60), 0);
        mypage(t2).andExpect(status().isOk());
        reissue(expire(t2, 60)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("REISSUE_LIMIT"));

        assertEquals(2, reissueNum(login()));
    }

    @Test @DisplayName("재발급은 만료 후 24시간 안에만: 23시간 59분 200, 24시간 1분 401 REISSUE_EXPIRED")
    void reissueOnlyWithin24h() throws Exception {
        String t = login();
        reissued(expire(t, 86400 - 60), 1);
        reissue(expire(t, 86400 + 60)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("REISSUE_EXPIRED"));
    }

    @Test @DisplayName("[의도된 취약점] reissue_num은 토큰에만 있어 처음 토큰(2)을 다시 보내면 계속 1로 재발급")
    void reissueReplayDocumented() throws Exception {
        String e = expire(login(), 60);
        for (int i = 0; i < 3; i++) reissued(e, 1);
    }

    @Test @DisplayName("재발급 때 DB를 다시 읽음: 권한 변경 반영, 제한 계정 403, 삭제된 회원 401")
    void reissueRereadsUser() throws Exception {
        String e = expire(login(), 60);
        jdbc.update("UPDATE users SET auth = 2 WHERE user_id = ?", uid);
        assertEquals("INSTRUCTOR", claims(reissued(e, 1)).get("role"));

        jdbc.update("UPDATE users SET status = 'restricted' WHERE user_id = ?", uid);
        reissue(e).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCOUNT_RESTRICTED"));

        jdbc.update("DELETE FROM users WHERE user_id = ?", uid);
        reissue(e).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    // ------------------------------------------------------------ 보호된 API 전부: 401

    /** 이름 + Authorization 값(null이면 헤더 없음)을 받아 요청을 만드는 보호된 API */
    static class Endpoint {
        final String name;
        final java.util.function.Function<String, MockHttpServletRequestBuilder> build;
        Endpoint(String name, java.util.function.Function<String, MockHttpServletRequestBuilder> build) {
            this.name = name;
            this.build = build;
        }
    }

    static MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder b, String authorization) {
        return authorization == null ? b : b.header("Authorization", authorization);
    }

    static Endpoint json(HttpMethod m, String url, String body) {
        return new Endpoint(m + " " + url, a -> auth(request(m, url).contentType(MediaType.APPLICATION_JSON).content(body), a));
    }

    static Endpoint noBody(HttpMethod m, String url) {
        return new Endpoint(m + " " + url, a -> auth(request(m, url), a));
    }

    /** 보호된 API 전부. 본문·쿼리·X-Reauth-Token은 올바른 값이라 Authorization만 문제가 된다 */
    List<Endpoint> protectedEndpoints(String reauthProfile, String reauthPassword) throws Exception {
        byte[] png = png();
        List<Endpoint> list = new ArrayList<>();
        list.add(noBody(HttpMethod.GET, "/mypage"));
        list.add(json(HttpMethod.POST, "/mypage/verify-password", "{\"pw\":\"" + PW + "\",\"purpose\":\"PROFILE_UPDATE\"}"));
        list.add(new Endpoint("PUT /mypage/profile", a -> auth(put("/mypage/profile").header("X-Reauth-Token", reauthProfile)
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"김수정\",\"nickname\":\"바뀐닉\",\"email\":\"changed@example.test\","
                        + "\"phone\":\"01012345678\",\"birthDate\":\"2000-03-15\"}"), a)));
        list.add(new Endpoint("PUT /mypage/password", a -> auth(put("/mypage/password").header("X-Reauth-Token", reauthPassword)
                .contentType(MediaType.APPLICATION_JSON).content("{\"newPw\":\"N3wPassw0rd!\",\"newPwCheck\":\"N3wPassw0rd!\"}"), a)));
        list.add(new Endpoint("POST /mypage/edit_prof", a -> auth(multipart("/mypage/edit_prof")
                .file(new MockMultipartFile("image", "me.png", "image/png", png)), a)));
        list.add(noBody(HttpMethod.GET, "/mypage/courses"));
        list.add(noBody(HttpMethod.GET, "/mypage/qna"));
        list.add(noBody(HttpMethod.GET, "/mypage/cart"));
        list.add(json(HttpMethod.POST, "/mypage/cart/add", "{\"courseId\":7}"));
        list.add(json(HttpMethod.DELETE, "/mypage/cart/del", "{\"courseIds\":[7]}"));
        list.add(noBody(HttpMethod.GET, "/mypage/bnf"));
        list.add(json(HttpMethod.POST, "/mypage/cpn", "{\"couponCode\":\"WELCOME2026ABCD\"}"));
        list.add(noBody(HttpMethod.GET, "/mypage/buy"));
        list.add(noBody(HttpMethod.GET, "/mypage/buy/cpn?courseIds=7&couponId=9201"));
        list.add(noBody(HttpMethod.GET, "/mypage/buy/pnt?courseIds=7&usePoint=0"));
        list.add(json(HttpMethod.POST, "/mypage/buy", "{\"courseIds\":[7],\"usePoint\":0,\"payMethod\":\"CARD\"}"));
        list.add(noBody(HttpMethod.GET, "/mypage/buy/history"));
        list.add(noBody(HttpMethod.GET, "/mypage/buy/history/1"));
        list.add(json(HttpMethod.POST, "/payment", "{\"orderId\":1,\"card\":{\"number\":\"4000000000001111\","
                + "\"expiry\":\"12/99\",\"cvc\":\"123\",\"installment\":0}}"));
        list.add(noBody(HttpMethod.POST, "/logout"));
        list.add(noBody(HttpMethod.POST, "/login/replc"));
        return list;
    }

    /** 문제가 있는 Authorization 값들 (null = 헤더 없음). 모두 401 UNAUTHORIZED여야 한다 */
    Map<String, String> badHeaders(String valid, String reauth) {
        String raw = valid.substring("Bearer ".length());
        Map<String, String> m = new LinkedHashMap<>();
        m.put("헤더 없음", null);
        m.put("빈 값", "");
        m.put("공백만", "   ");
        m.put("Bearer만", "Bearer");
        m.put("Bearer + 공백", "Bearer    ");
        m.put("소문자 bearer", "bearer " + raw);
        m.put("대문자 BEARER", "BEARER " + raw);
        m.put("Bearer 뒤 공백 없음", "Bearer" + raw);
        m.put("scheme 없이 토큰만", raw);
        m.put("Basic", "Basic " + Base64.getEncoder().encodeToString((EMAIL + ":" + PW).getBytes(StandardCharsets.UTF_8)));
        m.put("Token scheme", "Token " + raw);
        m.put("JWT 형식 아님", "Bearer not.a.jwt");
        m.put("점 두 개만", "Bearer ..");
        m.put("토큰 뒤 다른 값", "Bearer " + raw + " extra");
        m.put("다른 키로 서명", "Bearer " + Jwts.builder().claims(claims(valid))
                .signWith(Keys.hmacShaKeyFor("another-secret-another-secret-another-secret!!".getBytes(StandardCharsets.UTF_8)), Jwts.SIG.HS256).compact());
        m.put("서명 없음 (alg none)", "Bearer " + Jwts.builder().claims(claims(valid)).compact());
        m.put("같은 키 HS512", "Bearer " + Jwts.builder().claims(claims(valid)).signWith(KEY, Jwts.SIG.HS512).compact());
        m.put("페이로드 바꿔치기 (sub 변경, 서명 그대로)", tamperedSub(raw));
        m.put("reauth 토큰을 로그인 토큰으로", "Bearer " + reauth);
        return m;
    }

    /** 서명은 원래 것 그대로 두고 payload의 sub만 다른 회원 번호로 바꾼 토큰 */
    String tamperedSub(String raw) {
        String[] p = raw.split("\\.");
        String payload = new String(Base64.getUrlDecoder().decode(p[1]), StandardCharsets.UTF_8)
                .replace("\"sub\":\"" + uid + "\"", "\"sub\":\"" + (uid + 1) + "\"");
        return "Bearer " + p[0] + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + "." + p[2];
    }

    String reauthToken(String bearer, String purpose) throws Exception {
        MvcResult r = mvc.perform(post("/mypage/verify-password").header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"pw\":\"" + PW + "\",\"purpose\":\"" + purpose + "\"}"))
                .andExpect(status().isOk()).andReturn();
        return JsonPath.read(r.getResponse().getContentAsString(), "$.result.reauthToken");
    }

    @Test @DisplayName("보호된 API 전부: Authorization 없음·빈 값·Bearer 아님·형식 오류·위조·HS512·reauth 토큰 → 401 UNAUTHORIZED, 아무것도 바뀌지 않음")
    void everyProtectedEndpointRejectsBadAuthorization() throws Exception {
        String valid = login();
        String rp = reauthToken(valid, "PROFILE_UPDATE");
        String rw = reauthToken(valid, "PASSWORD_CHANGE");
        List<Endpoint> endpoints = protectedEndpoints(rp, rw);
        Map<String, String> bad = badHeaders(valid, rp);

        List<String> failures = new ArrayList<>();
        for (Endpoint e : endpoints) {
            for (Map.Entry<String, String> h : bad.entrySet()) {
                MvcResult r = mvc.perform(e.build.apply(h.getValue())).andReturn();
                String body = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
                // 현재 동작: 재발급은 만료 여부를 먼저 봐서, 아직 유효한 reauth 토큰은 401이 아니라 400 TOKEN_NOT_EXPIRED
                // (재발급은 되지 않음. 만료된 reauth 토큰은 401 UNAUTHORIZED, LoginApiTest.reissueBadTokens)
                if (e.name.equals("POST /login/replc") && h.getKey().startsWith("reauth")) {
                    if (r.getResponse().getStatus() != 400 || !body.contains("\"code\":\"TOKEN_NOT_EXPIRED\"")) {
                        failures.add(e.name + " / " + h.getKey() + " → " + r.getResponse().getStatus() + " " + body);
                    }
                    continue;
                }
                if (r.getResponse().getStatus() != 401 || !body.contains("\"code\":\"UNAUTHORIZED\"")) {
                    failures.add(e.name + " / " + h.getKey() + " → " + r.getResponse().getStatus() + " " + body);
                }
            }
        }
        assertTrue(failures.isEmpty(), String.join("\n", failures));
        assertUnchanged();
    }

    @Test @DisplayName("보호된 API 전부: 만료 토큰 → 401 TOKEN_EXPIRED (예외: 로그아웃은 204, 재발급은 정상 경로)")
    void everyProtectedEndpointRejectsExpiredToken() throws Exception {
        String valid = login();
        String expired = expire(valid, 60);
        List<String> failures = new ArrayList<>();
        for (Endpoint e : protectedEndpoints(reauthToken(valid, "PROFILE_UPDATE"), reauthToken(valid, "PASSWORD_CHANGE"))) {
            MvcResult r = mvc.perform(e.build.apply(expired)).andReturn();
            int s = r.getResponse().getStatus();
            String body = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
            boolean ok;
            if (e.name.equals("POST /logout")) ok = s == 204;
            else if (e.name.equals("POST /login/replc")) ok = s == 200;
            else ok = s == 401 && body.contains("\"code\":\"TOKEN_EXPIRED\"");
            if (!ok) failures.add(e.name + " → " + s + " " + body);
        }
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }

    @Test @DisplayName("본문이 깨져도 토큰을 먼저 봄: Authorization 없이 깨진 JSON → 400이 아니라 401")
    void tokenCheckedBeforeBrokenBody() throws Exception {
        String[][] posts = {{"POST", "/mypage/verify-password"}, {"PUT", "/mypage/profile"}, {"PUT", "/mypage/password"},
                {"POST", "/mypage/cart/add"}, {"DELETE", "/mypage/cart/del"}, {"POST", "/mypage/cpn"},
                {"POST", "/mypage/buy"}, {"POST", "/payment"}};
        List<String> failures = new ArrayList<>();
        for (String[] p : posts) {
            MvcResult r = mvc.perform(request(HttpMethod.valueOf(p[0]), p[1]).contentType(MediaType.APPLICATION_JSON).content("{broken")).andReturn();
            if (r.getResponse().getStatus() != 401) failures.add(p[0] + " " + p[1] + " → " + r.getResponse().getStatus());
        }
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }

    @Test @DisplayName("대조군: 같은 요청을 올바른 토큰으로 보내면 401이 아님 (위 401이 다른 이유로 난 것이 아님을 확인)")
    void validTokenPassesEveryEndpoint() throws Exception {
        String valid = login();
        Supplier<String> fresh = () -> valid;
        List<String> failures = new ArrayList<>();
        for (Endpoint e : protectedEndpoints(reauthToken(valid, "PROFILE_UPDATE"), reauthToken(valid, "PASSWORD_CHANGE"))) {
            if (e.name.equals("POST /login/replc")) continue;   // 유효 토큰은 400 TOKEN_NOT_EXPIRED가 정상
            int s = mvc.perform(e.build.apply(fresh.get())).andReturn().getResponse().getStatus();
            if (s == 401 || s >= 500) failures.add(e.name + " → " + s);
        }
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }

    /** 401 요청들이 DB를 바꾸지 않았는지 */
    void assertUnchanged() throws Exception {
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM cart", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM user_coupons", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM orders", Integer.class));
        Map<String, Object> u = jdbc.queryForMap("SELECT mail, nickname, picture FROM users WHERE user_id = ?", uid);
        assertEquals(EMAIL, u.get("MAIL"));
        assertEquals("토큰", u.get("NICKNAME"));
        assertNull(u.get("PICTURE"));
        login();   // 비밀번호도 그대로
    }

    static byte[] png() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB), "png", out);
        return out.toByteArray();
    }
}
