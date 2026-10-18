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
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 이메일 인증(Gmail SMTP 발송) → 확인 → 회원가입 → 로그인을 이어서 확인하는 흐름 테스트.
 * 자동 테스트에서는 실제 Gmail로 보낼 수 없으므로 JavaMailSender를 가짜(MockBean)로 바꾸고,
 * 「보냈을 메일」을 그대로 잡아 내용(보낸 사람·받는 사람·제목·본문)을 글자 단위로 확인한다.
 * 실제 Gmail 연결 확인은 GmailSmtpLiveTest (MAIL_USERNAME·MAIL_PASSWORD가 있을 때만 실행).
 * 시간은 테스트용 Clock 빈으로 앞으로 돌린다 (5분 만료, 30분 토큰, 60초·1시간 발송 제한). 기다리는 테스트 없음.
 *   gradlew test --tests com.example.sesacedu.EmailAuthFlowTest
 */
@SuppressWarnings("null") // VS Code(JDT) null 분석이 Spring/MockMvc API에 내는 경고 제외. 실행에는 영향 없음
@SpringBootTest(properties = "JWT_SECRET=" + EmailAuthFlowTest.SECRET)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class EmailAuthFlowTest {

    static final String SECRET = "email-auth-flow-secret-at-least-32-bytes!!";
    static final String PW = "Passw0rd!";
    static final String FROM = "test@example.test";    // application-test.yml app.mail.from
    static final String SUBJECT = "[온라인 강의] 회원가입 인증번호";

    /** 테스트에서 시간을 앞으로 돌릴 수 있는 시계 (EmailAuthStore가 Clock 빈을 쓴다) */
    static class MutableClock extends Clock {
        Instant now = Instant.parse("2026-10-13T00:00:00Z");
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
    @Autowired JdbcTemplate jdbc;
    @Autowired EmailAuthStore store;
    @Autowired MutableClock clock;
    @MockBean JavaMailSender mailSender;

    int ipSeq = 0;

    @BeforeEach
    void setUp() {
        clock.advance(Duration.ofHours(2));   // 이전 테스트의 발송 기록이 1시간 창 밖으로
        store.cleanup();
        jdbc.update("DELETE FROM users");
        reset(mailSender);
        ipSeq++;
    }

    String ip() { return "10.7." + ipSeq + ".1"; }

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

    ResultActions signup(String email, String token, String nickname) throws Exception {
        return mvc.perform(post("/signup").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"verificationToken\":\"" + token + "\",\"pw\":\"" + PW
                        + "\",\"pwcheck\":\"" + PW + "\",\"name\":\"김새싹\",\"nickname\":\"" + nickname
                        + "\",\"phone\":\"01011112222\",\"b_date\":\"2000-03-15\",\"address\":\"서울시 어딘가\"}"));
    }

    ResultActions login(String email) throws Exception {
        return mvc.perform(post("/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"pw\":\"" + PW + "\"}"));
    }

    /** 지금까지 보낸 가짜 메일 전부 */
    List<SimpleMailMessage> sent() {
        ArgumentCaptor<SimpleMailMessage> c = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender, atLeast(0)).send(c.capture());
        return c.getAllValues();
    }

    /** 마지막 메일의 6자리 인증번호 */
    String lastCode() {
        List<SimpleMailMessage> all = sent();
        assertFalse(all.isEmpty(), "보낸 메일이 있어야 함");
        Matcher m = Pattern.compile("인증번호: ([0-9]{6})").matcher(all.get(all.size() - 1).getText());
        assertTrue(m.find());
        return m.group(1);
    }

    String token(MvcResult r) throws Exception {
        return JsonPath.read(r.getResponse().getContentAsString(StandardCharsets.UTF_8), "$.result.verificationToken");
    }

    /** 발송 → 확인 → verificationToken */
    String verified(String email) throws Exception {
        send(email).andExpect(status().isOk());
        return token(verifyCode(email, lastCode()).andExpect(status().isOk()).andReturn());
    }

    // ------------------------------------------------------------

    @Test @DisplayName("메일 내용: 보낸 사람·받는 사람(소문자)·제목·본문이 정확하고, 번호는 API 응답에 없음 → 대소문자 섞어 확인·가입·로그인")
    void mailContentAndMixedCaseChain() throws Exception {
        MvcResult r = send("  Mixed.Case@Example.TEST ").andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("EMAIL_SENT"))
                .andExpect(jsonPath("$.result.expiresIn").value(300))
                .andExpect(jsonPath("$.result.resendAfter").value(60))
                .andReturn();

        List<SimpleMailMessage> mails = sent();
        assertEquals(1, mails.size());
        SimpleMailMessage m = mails.get(0);
        String code = lastCode();
        assertEquals(FROM, m.getFrom());
        assertArrayEquals(new String[]{"mixed.case@example.test"}, m.getTo());
        assertNull(m.getCc());
        assertNull(m.getBcc());
        assertEquals(SUBJECT, m.getSubject());
        assertEquals("인증번호: " + code + "\n5분 안에 회원가입 화면에 입력해주세요.\n본인이 요청하지 않았다면 이 메일을 무시하세요.", m.getText());
        assertFalse(r.getResponse().getContentAsString(StandardCharsets.UTF_8).contains(code), "응답에 번호가 있으면 안 됨");

        // 확인·가입·로그인 모두 대소문자를 무시하고 같은 이메일로 본다
        String vt = token(verifyCode("MIXED.case@example.test", code).andExpect(status().isOk()).andReturn());
        assertEquals(32, vt.length());
        signup("Mixed.CASE@example.TEST", vt, "섞인대소문자").andExpect(status().isCreated());
        assertEquals("mixed.case@example.test", jdbc.queryForObject("SELECT mail FROM users", String.class));
        login("MIXED.CASE@EXAMPLE.TEST").andExpect(status().isOk());

        // 가입된 이메일은 어떤 대소문자로도 409, 메일은 더 나가지 않음
        send("mixed.CASE@Example.test").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_EMAIL"));
        assertEquals(1, sent().size());
    }

    @Test @DisplayName("발송 제한: 60초 안 재요청 429 → 60초 뒤 새 번호(이전 번호 무효) → 이메일당 1시간 5회 → 1시간 뒤 다시 가능, 429는 메일을 보내지 않음")
    void sendLimitsThroughTheChain() throws Exception {
        String email = "limit@example.test";
        send(email).andExpect(status().isOk());
        String first = lastCode();

        clock.advance(Duration.ofSeconds(30));
        send(email).andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "30"))
                .andExpect(jsonPath("$.result.retryAfter").value(30));
        assertEquals(1, sent().size());

        clock.advance(Duration.ofSeconds(30));
        send(email).andExpect(status().isOk());
        String second = lastCode();
        if (!second.equals(first)) {    // 같은 번호가 다시 나올 확률 100만분의 1
            verifyCode(email, first).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_CODE"));
        }

        // 3·4·5번째 (60초 간격) → 6번째는 첫 발송 + 1시간까지 대기
        for (int i = 3; i <= 5; i++) {
            clock.advance(Duration.ofSeconds(60));
            send(email).andExpect(status().isOk());
        }
        clock.advance(Duration.ofSeconds(60));   // 첫 발송 + 300초
        send(email).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.result.retryAfter").value(3600 - 300));
        assertEquals(5, sent().size());

        // 그동안 받은 마지막 번호는 유효 (막혀도 기존 번호는 그대로)
        String fifth = lastCode();
        verifyCode(email, fifth).andExpect(status().isOk());

        clock.advance(Duration.ofSeconds(3600 - 300 + 1));
        send(email).andExpect(status().isOk());
        assertEquals(6, sent().size());
    }

    @Test @DisplayName("발송 제한: IP당 1시간 20회 (다른 IP는 영향 없음)")
    void perIpLimit() throws Exception {
        for (int i = 0; i < 20; i++) send("ip" + i + "@example.test", "10.7.200.1").andExpect(status().isOk());
        send("ip20@example.test", "10.7.200.1").andExpect(status().isTooManyRequests());
        send("ip20@example.test", "10.7.200.2").andExpect(status().isOk());
        assertEquals(21, sent().size());
    }

    @Test @DisplayName("틀린 번호·만료된 번호: 틀리면 INVALID_CODE(횟수 제한 없음 = 의도된 취약점), 5분 지나면 맞아도 CODE_EXPIRED, 재발송 번호는 동작")
    void wrongAndExpiredCode() throws Exception {
        String email = "code@example.test";
        verifyCode(email, "123456").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("CODE_EXPIRED"));

        send(email).andExpect(status().isOk());
        String code = lastCode();
        String wrong = code.equals("000000") ? "000001" : "000000";
        for (int i = 0; i < 3; i++) {
            verifyCode(email, wrong).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_CODE"));
        }
        assertEquals(3, store.triesOf(email));
        verifyCode(email, "12345").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));

        // 5분 0초까지는 유효, 넘으면 맞는 번호도 만료
        clock.advance(Duration.ofMinutes(5).plusSeconds(1));
        verifyCode(email, code).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("CODE_EXPIRED"));

        send(email).andExpect(status().isOk());
        String vt = token(verifyCode(email, lastCode()).andExpect(status().isOk()).andReturn());
        signup(email, vt, "번호확인").andExpect(status().isCreated());
    }

    @Test @DisplayName("verificationToken: 다른 이메일에 못 씀, 30분 뒤 만료, 재발송하면 무효, 가입에 한 번만")
    void verificationTokenRules() throws Exception {
        // 다른 이메일의 토큰
        String a = verified("token-a@example.test");
        verified("token-b@example.test");
        signup("token-b@example.test", a, "남의토큰").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_VERIFICATION"));

        // 확인 후 다시 발송하면 이전 토큰은 버려짐
        clock.advance(Duration.ofSeconds(61));
        send("token-a@example.test").andExpect(status().isOk());
        signup("token-a@example.test", a, "재발송뒤").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_VERIFICATION"));

        // 30분: 29분이면 가입, 31분이면 만료
        String c = verified("token-c@example.test");
        String d = verified("token-d@example.test");
        clock.advance(Duration.ofMinutes(29));
        signup("token-c@example.test", c, "이십구분").andExpect(status().isCreated());
        clock.advance(Duration.ofMinutes(2));
        signup("token-d@example.test", d, "삼십일분").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_VERIFICATION"));

        // 1회용: 같은 토큰으로 두 번째 가입은 INVALID_VERIFICATION
        String e = verified("token-e@example.test");
        signup("token-e@example.test", e, "한번만").andExpect(status().isCreated());
        signup("token-e@example.test", e, "두번째").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_VERIFICATION"));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM users", Integer.class));
    }

    @Test @DisplayName("SMTP 실패: 503 EMAIL_SEND_FAILED, 번호는 저장되지 않고 횟수에도 안 들어가 바로 재시도 가능")
    void smtpFailureThenRetry() throws Exception {
        String email = "smtp-down@example.test";
        doThrow(new MailSendException("smtp down")).when(mailSender).send(any(SimpleMailMessage.class));
        send(email).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("EMAIL_SEND_FAILED"))
                .andExpect(jsonPath("$.message", not(containsString("smtp down"))));
        Matcher m = Pattern.compile("인증번호: ([0-9]{6})").matcher(sent().get(0).getText());
        assertTrue(m.find());
        verifyCode(email, m.group(1)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("CODE_EXPIRED"));

        reset(mailSender);
        send(email).andExpect(status().isOk());
        String vt = token(verifyCode(email, lastCode()).andExpect(status().isOk()).andReturn());
        signup(email, vt, "재시도성공").andExpect(status().isCreated());
    }
}
