package com.example.sesacedu;

import com.example.sesacedu.auth.EmailAuthStore;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * auth 패키지의 email_auth / email_auth_verify / signup 이 Notion 명세(이메일 중복 및 인증번호 요청,
 * 이메일 인증번호 확인, 회원가입)대로 응답하는지 확인.
 * 메일은 가짜(MockBean JavaMailSender)라 실제로 나가지 않는다. 보낸 메일 본문에서 인증번호를 꺼내 쓴다.
 * 시간은 테스트용 Clock 빈으로 앞으로 돌린다 (5분 만료, 30분 토큰, 60초·1시간 발송 제한).
 * 명세의 응답 코드를 모두 확인한다:
 *   POST /email-auth        200 EMAIL_SENT / 400 INVALID_INPUT / 409 DUPLICATE_EMAIL / 429 TOO_MANY_REQUESTS
 *                           / 503 EMAIL_SEND_FAILED / 500 INTERNAL_ERROR
 *   POST /email-auth/verify 200 EMAIL_VERIFIED / 400 INVALID_INPUT·INVALID_CODE·CODE_EXPIRED / 500 INTERNAL_ERROR
 *   POST /signup            201 SIGNUP_SUCCESS / 400 INVALID_INPUT·INVALID_VERIFICATION
 *                           / 409 DUPLICATE_EMAIL·DUPLICATE_NICKNAME / 500 INTERNAL_ERROR
 * 500은 EmailAuthStore·JdbcTemplate을 SpyBean으로 감싸 일부러 예외를 던지게 해서 확인한다.
 * H2(Oracle 모드, application-test.yml + schema-test.sql)로 실행:
 *   gradlew test --tests com.example.sesacedu.SignupApiTest
 */
@SpringBootTest(properties = "JWT_SECRET=" + SignupApiTest.SECRET)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SignupApiTest {

    static final String SECRET = "signup-api-test-secret-at-least-32-bytes!";
    static final String PW = "Passw0rd!";

    /** 테스트에서 시간을 앞으로 돌릴 수 있는 시계 */
    static class MutableClock extends Clock {
        Instant now = Instant.parse("2026-10-12T00:00:00Z");
        void advance(Duration d) { now = now.plus(d); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @TestConfiguration
    static class ClockConfig {
        @Bean MutableClock testClock() { return new MutableClock(); }
    }

    @Autowired MockMvc mvc;
    @SpyBean JdbcTemplate jdbc;          // 평소엔 진짜 동작. 500·동시 가입 테스트에서만 일부 메서드를 바꾼다
    @SpyBean EmailAuthStore store;
    @Autowired MutableClock clock;
    @MockBean JavaMailSender mailSender;

    int ipSeq = 0;

    @BeforeEach
    void setUp() {
        // 이전 테스트의 인증·발송 기록을 정리: 2시간 뒤로 돌리고 정리 작업 실행
        clock.advance(Duration.ofHours(2));
        store.cleanup();
        jdbc.update("DELETE FROM users");
        jdbc.update("INSERT INTO users (mail, pw, uname, nickname, auth) VALUES (?, ?, ?, ?, 1)",
                "taken@example.com", DigestUtils.md5DigestAsHex(PW.getBytes(StandardCharsets.UTF_8)), "김태형", "태형");
        reset(mailSender);
        ipSeq++;
    }

    String ip() { return "10.0.0." + ipSeq; }

    ResultActions send(String email) throws Exception {
        return send(email, ip());
    }

    ResultActions send(String email, String ip) throws Exception {
        return mvc.perform(post("/email-auth").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\"}").with(r -> { r.setRemoteAddr(ip); return r; }));
    }

    ResultActions verifyCode(String email, String code) throws Exception {
        return mvc.perform(post("/email-auth/verify").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"code\":\"" + code + "\"}"));
    }

    ResultActions signup(String body) throws Exception {
        return mvc.perform(post("/signup").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    static String signupJson(String email, String token, String nickname) {
        return "{\"email\":\"" + email + "\",\"verificationToken\":\"" + token + "\",\"pw\":\"" + PW
                + "\",\"pwcheck\":\"" + PW + "\",\"name\":\"홍지승\",\"nickname\":\"" + nickname
                + "\",\"b_date\":\"1998-01-01\",\"address\":\"서울특별시 노원구 공릉로 232\"}";
    }

    /** 가장 최근에 보낸 가짜 메일에서 6자리 인증번호 꺼내기 */
    String lastCode(String to) {
        ArgumentCaptor<SimpleMailMessage> c = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender, atLeastOnce()).send(c.capture());
        SimpleMailMessage m = c.getValue();
        assertArrayEquals(new String[]{to}, m.getTo());
        java.util.regex.Matcher x = java.util.regex.Pattern.compile("인증번호: ([0-9]{6})").matcher(m.getText());
        assertTrue(x.find(), "메일 본문에 6자리 인증번호가 있어야 함");
        return x.group(1);
    }

    /** 발송 → 확인까지 하고 verificationToken 반환 */
    String verifiedToken(String email) throws Exception {
        send(email).andExpect(status().isOk());
        String body = verifyCode(email, lastCode(email)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return JsonPath.read(body, "$.result.verificationToken");
    }

    // ------------------------------------------------------------ POST /email-auth

    @Test @DisplayName("발송 200 EMAIL_SENT: expiresIn 300, resendAfter 60, 번호는 응답에 없고 메일로만")
    void sendSuccess() throws Exception {
        send("  New@Example.COM ")                          // 공백 제거 + 소문자 변환
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("EMAIL_SENT"))
                .andExpect(jsonPath("$.message").value("인증번호가 발송되었습니다."))
                .andExpect(jsonPath("$.result.expiresIn").value(300))
                .andExpect(jsonPath("$.result.resendAfter").value(60))
                .andExpect(jsonPath("$.result.code").doesNotExist());
        assertEquals(6, lastCode("new@example.com").length());
    }

    @Test @DisplayName("발송 400 INVALID_INPUT: 누락·형식 오류·깨진 JSON, 메일 안 보냄")
    void sendInvalidInput() throws Exception {
        String longEmail = "a".repeat(89) + "@example.com";             // 101자
        for (String body : new String[]{"{}", "{\"email\":\"not-an-email\"}", "{\"email\":\"" + longEmail + "\"}", "{broken", ""}) {
            mvc.perform(post("/email-auth").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                    .andExpect(jsonPath("$.message").value("입력값을 확인해주세요."))
                    .andExpect(jsonPath("$.result.errors").isArray());
        }
        verify(mailSender, never()).send(any(SimpleMailMessage.class));
    }

    @Test @DisplayName("발송 409 DUPLICATE_EMAIL: 가입된 이메일 노출 (의도된 취약점), 메일 안 보냄")
    void sendDuplicateEmail() throws Exception {
        send("TAKEN@example.com")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_EMAIL"))
                .andExpect(jsonPath("$.message").value("이미 가입된 이메일입니다."))
                .andExpect(jsonPath("$.result").value(nullValue()));
        verify(mailSender, never()).send(any(SimpleMailMessage.class));
    }

    @Test @DisplayName("발송 429: 60초 안 재요청은 retryAfter + Retry-After 헤더, 60초 뒤엔 200이고 이전 번호는 무효")
    void resendWithin60Seconds() throws Exception {
        send("a@example.com").andExpect(status().isOk());
        String first = lastCode("a@example.com");
        clock.advance(Duration.ofSeconds(18));
        send("a@example.com")
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "42"))
                .andExpect(jsonPath("$.code").value("TOO_MANY_REQUESTS"))
                .andExpect(jsonPath("$.message").value("잠시 후 다시 시도해주세요."))
                .andExpect(jsonPath("$.result.retryAfter").value(42));
        clock.advance(Duration.ofSeconds(42));
        send("a@example.com").andExpect(status().isOk());
        String second = lastCode("a@example.com");
        if (!first.equals(second)) {                       // 우연히 같은 번호가 나온 경우만 건너뜀
            verifyCode("a@example.com", first).andExpect(jsonPath("$.code").value("INVALID_CODE"));
        }
        verifyCode("a@example.com", second).andExpect(status().isOk());
    }

    @Test @DisplayName("발송 429: 이메일당 1시간 5회, IP당 1시간 20회")
    void sendLimits() throws Exception {
        for (int i = 0; i < 5; i++) {
            send("b@example.com").andExpect(status().isOk());
            clock.advance(Duration.ofSeconds(61));
        }
        send("b@example.com").andExpect(status().isTooManyRequests());
        clock.advance(Duration.ofHours(1));
        send("b@example.com").andExpect(status().isOk());

        String ip = "192.0.2.7";
        for (int i = 0; i < 20; i++) send("ip" + i + "@example.com", ip).andExpect(status().isOk());
        send("ip20@example.com", ip).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("TOO_MANY_REQUESTS"));
        send("ip20@example.com", "192.0.2.8").andExpect(status().isOk());   // 다른 IP는 영향 없음
    }

    @Test @DisplayName("발송 503 EMAIL_SEND_FAILED: 번호를 저장하지 않고, 실패는 429 횟수에 넣지 않음")
    void sendFailed() throws Exception {
        doThrow(new MailSendException("535 auth failed")).when(mailSender).send(any(SimpleMailMessage.class));
        send("c@example.com")
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("EMAIL_SEND_FAILED"))
                .andExpect(jsonPath("$.message").value("인증 메일 발송에 실패했습니다. 잠시 후 다시 시도해주세요."))
                .andExpect(jsonPath("$.result").value(nullValue()));
        String failedCode = lastCode("c@example.com");
        verifyCode("c@example.com", failedCode).andExpect(jsonPath("$.code").value("CODE_EXPIRED"));

        doNothing().when(mailSender).send(any(SimpleMailMessage.class));
        send("c@example.com").andExpect(status().isOk());   // 바로 다시 요청해도 429 아님
    }

    @Test @DisplayName("발송 500 INTERNAL_ERROR: 원인은 응답에 넣지 않음, 메일 안 보냄")
    void sendInternalError() throws Exception {
        doThrow(new IllegalStateException("db down")).when(store).retryAfterSeconds(anyString(), anyString());
        send("z@example.com")
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("서버 오류가 발생했습니다."))
                .andExpect(jsonPath("$.result").value(nullValue()))
                .andExpect(content().string(not(containsString("db down"))));
        verify(mailSender, never()).send(any(SimpleMailMessage.class));
    }

    // ------------------------------------------------------------ POST /email-auth/verify

    @Test @DisplayName("확인 200 EMAIL_VERIFIED: verificationToken 32자, expiresIn 1800")
    void verifySuccess() throws Exception {
        send("d@example.com").andExpect(status().isOk());
        verifyCode("D@Example.com ", lastCode("d@example.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("EMAIL_VERIFIED"))
                .andExpect(jsonPath("$.message").value("이메일 인증이 완료되었습니다."))
                .andExpect(jsonPath("$.result.verificationToken", matchesPattern("^[0-9a-f]{32}$")))
                .andExpect(jsonPath("$.result.expiresIn").value(1800));
    }

    @Test @DisplayName("확인 400 INVALID_INPUT: code는 숫자 6자리 문자열")
    void verifyInvalidInput() throws Exception {
        verifyCode("bad", "12345")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.result.errors[*].field", containsInAnyOrder("email", "code")));
        verifyCode("d@example.com", "12a456").andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        verifyCode("d@example.com", "1234567").andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        for (String body : new String[]{"{}", "{broken", ""}) {
            mvc.perform(post("/email-auth/verify").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                    .andExpect(jsonPath("$.message").value("입력값을 확인해주세요."));
        }
    }

    @Test @DisplayName("확인 횟수 제한 없음 (의도된 취약점): 30번 틀려도 맞는 번호면 200, tries에는 기록")
    void verifyNoTryLimit() throws Exception {
        send("e@example.com").andExpect(status().isOk());
        String code = lastCode("e@example.com");
        String wrong = code.equals("000000") ? "000001" : "000000";
        for (int i = 0; i < 30; i++) {
            verifyCode("e@example.com", wrong)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_CODE"))
                    .andExpect(jsonPath("$.message").value("인증번호가 올바르지 않습니다."))
                    .andExpect(jsonPath("$.result").value(nullValue()));
        }
        assertEquals(30, store.triesOf("e@example.com"));
        verifyCode("e@example.com", code).andExpect(status().isOk());
    }

    @Test @DisplayName("확인 400 CODE_EXPIRED: 발송 이력 없음, 5분 지남")
    void verifyExpired() throws Exception {
        verifyCode("nobody@example.com", "123456").andExpect(jsonPath("$.code").value("CODE_EXPIRED"));
        send("f@example.com").andExpect(status().isOk());
        String code = lastCode("f@example.com");
        clock.advance(Duration.ofMinutes(5).plusSeconds(1));
        verifyCode("f@example.com", code)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CODE_EXPIRED"))
                .andExpect(jsonPath("$.message").value("인증번호가 만료되었습니다. 다시 요청해주세요."));
    }

    @Test @DisplayName("확인 500 INTERNAL_ERROR: 원인은 응답에 넣지 않음")
    void verifyInternalError() throws Exception {
        doThrow(new IllegalStateException("boom")).when(store).verify(anyString(), anyString());
        verifyCode("y@example.com", "123456")
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("서버 오류가 발생했습니다."))
                .andExpect(content().string(not(containsString("boom"))));
    }

    // ------------------------------------------------------------ POST /signup

    @Test @DisplayName("가입 201 SIGNUP_SUCCESS: memberId, MD5 비밀번호, auth 0 / active, 토큰은 1회용")
    void signupSuccess() throws Exception {
        String token = verifiedToken("new@example.com");
        String body = signup(signupJson(" New@Example.com", token, "jade"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("SIGNUP_SUCCESS"))
                .andExpect(jsonPath("$.message").value("회원가입이 완료되었습니다."))
                .andExpect(jsonPath("$.result.memberId").isNumber())
                .andExpect(header().doesNotExist("Authorization"))
                .andExpect(header().doesNotExist("Location"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        long memberId = ((Number) JsonPath.read(body, "$.result.memberId")).longValue();

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT mail, pw, uname, nickname, b_date, address, auth, status FROM users WHERE user_id = ?", memberId);
        assertEquals("new@example.com", row.get("MAIL"));
        assertEquals(DigestUtils.md5DigestAsHex(PW.getBytes(StandardCharsets.UTF_8)), row.get("PW"));
        assertEquals("홍지승", row.get("UNAME"));
        assertEquals("jade", row.get("NICKNAME"));
        assertTrue(String.valueOf(row.get("B_DATE")).startsWith("1998-01-01"));
        assertEquals(0, ((Number) row.get("AUTH")).intValue());
        assertEquals("active", row.get("STATUS"));

        // 같은 토큰 재사용 → 이미 쓴 토큰
        signup(signupJson("new@example.com", token, "jade2"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_VERIFICATION"));

        // 가입한 계정으로 로그인 가능
        mvc.perform(post("/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"new@example.com\",\"pw\":\"" + PW + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.member.role").value("GENERAL"));
    }

    @Test @DisplayName("가입: 요청의 auth·status는 무시 (Mass Assignment 없음)")
    void signupIgnoresAuth() throws Exception {
        String token = verifiedToken("g@example.com");
        String json = signupJson("g@example.com", token, "gg").replace("{", "{\"auth\":3,\"status\":\"restricted\",");
        signup(json).andExpect(status().isCreated());
        Map<String, Object> row = jdbc.queryForMap("SELECT auth, status FROM users WHERE mail = 'g@example.com'");
        assertEquals(0, ((Number) row.get("AUTH")).intValue());
        assertEquals("active", row.get("STATUS"));
    }

    @Test @DisplayName("가입 400 INVALID_INPUT: 틀린 필드를 result.errors에 한 번에 모두")
    void signupInvalidInput() throws Exception {
        String body = "{\"email\":\"x\",\"pw\":\"password\",\"pwcheck\":\"different\",\"name\":\"열한글자가넘는이름입니다\","
                + "\"nickname\":\"a\",\"b_date\":\"2026-02-30\",\"address\":\"\"}";
        signup(body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.result.errors[*].field", containsInAnyOrder(
                        "email", "verificationToken", "pw", "pwcheck", "name", "nickname", "b_date", "address")));
        // 오늘·미래 생년월일, 깨진 JSON
        String token = verifiedToken("h@example.com");
        signup(signupJson("h@example.com", token, "hh").replace("1998-01-01", "2999-01-01"))
                .andExpect(jsonPath("$.result.errors[0].field").value("b_date"));
        signup("{broken").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        signup("").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        // 길이 상한: 이메일 101자, 닉네임 21자, 주소 101자 / 비밀번호 특수문자 없음·31자
        String longEmail = "a".repeat(89) + "@example.com";
        String tooLong = signupJson(longEmail, token, "n".repeat(21)).replace("서울특별시 노원구 공릉로 232", "주".repeat(101));
        signup(tooLong)
                .andExpect(jsonPath("$.message").value("입력값을 확인해주세요."))
                .andExpect(jsonPath("$.result.errors[*].field", containsInAnyOrder("email", "nickname", "address")));
        for (String badPw : new String[]{"Password1", "Pass1!", "Passw0rd!" + "x".repeat(22)}) {
            signup(signupJson("h@example.com", token, "hh").replace(PW, badPw))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.result.errors[*].field", org.hamcrest.Matchers.contains("pw")));
        }
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM users", Integer.class));   // 아무것도 가입 안 됨
    }

    @Test @DisplayName("가입 400 INVALID_VERIFICATION: 틀린 토큰, 다른 이메일의 토큰, 인증 후 30분 지남")
    void signupInvalidVerification() throws Exception {
        String token = verifiedToken("i@example.com");
        signup(signupJson("i@example.com", "0".repeat(32), "ii"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_VERIFICATION"))
                .andExpect(jsonPath("$.message").value("이메일 인증이 만료되었거나 올바르지 않습니다. 다시 인증해주세요."))
                .andExpect(jsonPath("$.result").value(nullValue()));
        signup(signupJson("never@example.com", "0".repeat(32), "ii"))       // 인증한 적 없는 이메일
                .andExpect(jsonPath("$.code").value("INVALID_VERIFICATION"));
        signup(signupJson("other@example.com", token, "ii"))
                .andExpect(jsonPath("$.code").value("INVALID_VERIFICATION"));
        clock.advance(Duration.ofMinutes(30).plusSeconds(1));
        signup(signupJson("i@example.com", token, "ii"))
                .andExpect(jsonPath("$.code").value("INVALID_VERIFICATION"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM users", Integer.class));
    }

    @Test @DisplayName("가입 409: DUPLICATE_NICKNAME (앱 검사), DUPLICATE_EMAIL (인증 뒤 다른 가입이 먼저)")
    void signupDuplicates() throws Exception {
        String token = verifiedToken("j@example.com");
        signup(signupJson("j@example.com", token, "태형"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_NICKNAME"))
                .andExpect(jsonPath("$.message").value("이미 사용 중인 닉네임입니다."));

        jdbc.update("INSERT INTO users (mail, pw, uname, nickname) VALUES ('j@example.com', 'x', '선수', '선수')");
        signup(signupJson("j@example.com", token, "jj"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_EMAIL"))
                .andExpect(jsonPath("$.message").value("이미 사용 중인 이메일입니다."));
    }

    @Test @DisplayName("가입 409 DUPLICATE_EMAIL: 중복 검사 뒤 INSERT 사이에 같은 이메일이 먼저 가입 (UNIQUE 위반을 409로)")
    void signupDuplicateEmailRace() throws Exception {
        String token = verifiedToken("k@example.com");
        jdbc.update("INSERT INTO users (mail, pw, uname, nickname) VALUES ('k@example.com', 'x', '선수', '선수')");
        // 3단계 중복 검사가 '없음'으로 보였다고 가정 (동시 요청 재현)
        doReturn(0).when(jdbc).queryForObject("SELECT COUNT(*) FROM users WHERE mail = ?", Integer.class, "k@example.com");
        signup(signupJson("k@example.com", token, "kk"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_EMAIL"))
                .andExpect(jsonPath("$.result").value(nullValue()));
    }

    @Test @DisplayName("가입 500 INTERNAL_ERROR: 원인은 응답에 넣지 않고, 가입도 안 됨")
    void signupInternalError() throws Exception {
        String token = verifiedToken("l@example.com");
        doThrow(new IllegalStateException("store broken")).when(store).isVerified(anyString(), anyString());
        signup(signupJson("l@example.com", token, "ll"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("서버 오류가 발생했습니다."))
                .andExpect(jsonPath("$.result").value(nullValue()))
                .andExpect(content().string(not(containsString("store broken"))));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM users", Integer.class));
    }

    @Test @DisplayName("전체 흐름: 발송 → 확인 → 가입 → 로그인 → 로그아웃")
    void fullFlow() throws Exception {
        String token = verifiedToken("flow@example.com");
        signup(signupJson("flow@example.com", token, "flow")).andExpect(status().isCreated());
        String auth = mvc.perform(post("/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"flow@example.com\",\"pw\":\"" + PW + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getHeader("Authorization");
        mvc.perform(post("/logout").header("Authorization", auth)).andExpect(status().isNoContent());
        send("flow@example.com").andExpect(status().isConflict());           // 가입 뒤엔 다시 발송 불가
    }
}
