package com.example.sesacedu;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.util.FileSystemUtils;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
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
 * 여러 API를 실제 순서대로 이어서 호출하는 흐름 테스트.
 * 토큰은 손으로 만들지 않고 모두 API가 준 것을 그대로 다음 API에 넘긴다
 * (인증번호 → verificationToken → 로그인 JWT → reauthToken).
 *   이메일 인증 → 회원가입 → 로그인 → 마이페이지 → 본인 확인 → 프로필 수정 → 사진 변경 → 비밀번호 변경 → 다시 로그인 → 로그아웃
 * 메일은 가짜(MockBean JavaMailSender)이고, 보낸 메일 본문에서 인증번호를 꺼낸다.
 * H2(Oracle 모드, application-test.yml + schema-test.sql)로 실행:
 *   gradlew test --tests com.example.sesacedu.UserFlowTest
 */
@SuppressWarnings("null") // VS Code(JDT) null 분석이 Spring/MockMvc API에 내는 경고 제외. 실행에는 영향 없음
@SpringBootTest(properties = {
        "JWT_SECRET=" + UserFlowTest.SECRET,
        "app.upload.profile-dir=" + UserFlowTest.UPLOAD_DIR})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UserFlowTest {

    static final String SECRET = "user-flow-test-secret-at-least-32-bytes!!";
    static final String UPLOAD_DIR = "build/test-flow-images";
    static final String PW = "Passw0rd!";
    static final String NEW_PW = "N3wPassw0rd!";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @MockBean JavaMailSender mailSender;

    static int ipSeq = 0;

    @BeforeEach
    void setUp() {
        FileSystemUtils.deleteRecursively(Paths.get(UPLOAD_DIR).toFile());
        jdbc.update("DELETE FROM users");
        reset(mailSender);
    }

    // ------------------------------------------------------------ 단계별 호출 (응답 값을 그대로 돌려줌)

    /** POST /email-auth → 메일의 인증번호 → POST /email-auth/verify → verificationToken */
    String emailVerified(String email) throws Exception {
        String ip = "10.9.0." + (++ipSeq);
        mvc.perform(post("/email-auth").contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"" + email + "\"}")
                        .with(r -> { r.setRemoteAddr(ip); return r; }))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("EMAIL_SENT"));
        ArgumentCaptor<SimpleMailMessage> c = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender, atLeastOnce()).send(c.capture());
        Matcher m = Pattern.compile("인증번호: ([0-9]{6})").matcher(c.getValue().getText());
        assertTrue(m.find());
        MvcResult r = mvc.perform(post("/email-auth/verify").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"code\":\"" + m.group(1) + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("EMAIL_VERIFIED")).andReturn();
        return JsonPath.read(r.getResponse().getContentAsString(StandardCharsets.UTF_8), "$.result.verificationToken");
    }

    /** 이메일 인증 + POST /signup → memberId */
    long signup(String email, String nickname) throws Exception {
        String vt = emailVerified(email);
        MvcResult r = mvc.perform(post("/signup").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"verificationToken\":\"" + vt + "\",\"pw\":\"" + PW
                                + "\",\"pwcheck\":\"" + PW + "\",\"name\":\"김새싹\",\"nickname\":\"" + nickname
                                + "\",\"phone\":\"01011112222\",\"b_date\":\"2000-03-15\",\"address\":\"서울시 어딘가\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.code").value("SIGNUP_SUCCESS")).andReturn();
        return ((Number) JsonPath.read(r.getResponse().getContentAsString(StandardCharsets.UTF_8), "$.result.memberId")).longValue();
    }

    ResultActions login(String email, String pw) throws Exception {
        return mvc.perform(post("/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"pw\":\"" + pw + "\"}"));
    }

    /** POST /login 성공 → 응답 헤더의 "Bearer xxx" 그대로 */
    String loginBearer(String email, String pw) throws Exception {
        MvcResult r = login(email, pw).andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("LOGIN_SUCCESS")).andReturn();
        String h = r.getResponse().getHeader("Authorization");
        assertNotNull(h);
        assertTrue(h.startsWith("Bearer "));
        return h;
    }

    ResultActions verifyPassword(String bearer, String pw, String purpose) throws Exception {
        return mvc.perform(post("/mypage/verify-password").header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON).content("{\"pw\":\"" + pw + "\",\"purpose\":\"" + purpose + "\"}"));
    }

    String reauth(String bearer, String pw, String purpose) throws Exception {
        MvcResult r = verifyPassword(bearer, pw, purpose).andExpect(status().isOk()).andReturn();
        return JsonPath.read(r.getResponse().getContentAsString(), "$.result.reauthToken");
    }

    ResultActions putProfile(String bearer, String reauth, String nickname, String email) throws Exception {
        return mvc.perform(put("/mypage/profile").header("Authorization", bearer).header("X-Reauth-Token", reauth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"김수정\",\"nickname\":\"" + nickname + "\",\"email\":\"" + email
                        + "\",\"phone\":\"01012345678\",\"birthDate\":\"2000-03-15\"}"));
    }

    ResultActions putPassword(String bearer, String reauth, String newPw) throws Exception {
        return mvc.perform(put("/mypage/password").header("Authorization", bearer).header("X-Reauth-Token", reauth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"newPw\":\"" + newPw + "\",\"newPwCheck\":\"" + newPw + "\"}"));
    }

    static byte[] png() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(640, 480, BufferedImage.TYPE_INT_RGB), "png", out);
        return out.toByteArray();
    }

    // ------------------------------------------------------------ 흐름

    @Test @DisplayName("한 회원의 전체 흐름: 가입 → 로그인 → 마이페이지 → 프로필·사진·비밀번호 변경 → 다시 로그인 → 로그아웃")
    void fullUserJourney() throws Exception {
        // 1. 이메일 인증 + 회원가입
        long id = signup("flow@example.test", "흐름회원");

        // 2. 로그인 → 마이페이지 (가입한 값 그대로, 일반 회원)
        String t1 = loginBearer("Flow@Example.test", PW);
        mvc.perform(get("/mypage").header("Authorization", t1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.memberId").value(id))
                .andExpect(jsonPath("$.result.email").value("flow@example.test"))
                .andExpect(jsonPath("$.result.nickname").value("흐름회원"))
                .andExpect(jsonPath("$.result.birthDate").value("2000-03-15"))
                .andExpect(jsonPath("$.result.phone").value("01011112222"))
                .andExpect(jsonPath("$.result.profileImageUrl").value(nullValue()))
                .andExpect(jsonPath("$.result.role").value("GENERAL"));

        // 3. 본인 확인 없이 프로필 수정 → 403, 본인 확인 후 → 200 (이메일·닉네임 변경)
        putProfile(t1, "none", "새닉네임", "moved@example.test").andExpect(status().isForbidden());
        String profileReauth = reauth(t1, PW, "PROFILE_UPDATE");
        putProfile(t1, profileReauth, "새닉네임", "Moved@Example.test")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.email").value("moved@example.test"))
                .andExpect(jsonPath("$.result.phone").value("01012345678"));

        // 4. 바뀐 이메일로만 로그인 가능
        login("flow@example.test", PW).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("LOGIN_FAILED"));
        String t2 = loginBearer("moved@example.test", PW);
        mvc.perform(get("/mypage").header("Authorization", t2))
                .andExpect(jsonPath("$.result.nickname").value("새닉네임"))
                .andExpect(jsonPath("$.result.name").value("김수정"));

        // 5. 사진 변경 → 마이페이지에 같은 URL → 토큰 없이 이미지 열림
        MvcResult up = mvc.perform(multipart("/mypage/edit_prof")
                        .file(new MockMultipartFile("image", "me.png", "image/png", png()))
                        .header("Authorization", t2))
                .andExpect(status().isOk()).andReturn();
        String url = JsonPath.read(up.getResponse().getContentAsString(), "$.result.profileImageUrl");
        assertTrue(url.startsWith("/images/profile/" + id + "_profile.png?v="), url);
        mvc.perform(get("/mypage").header("Authorization", t2)).andExpect(jsonPath("$.result.profileImageUrl").value(url));
        mvc.perform(get(url.substring(0, url.indexOf('?')))).andExpect(status().isOk()).andExpect(content().contentType("image/png"));

        // 6. 프로필용 reauth 토큰으로는 비밀번호를 못 바꿈 → 비밀번호용으로 본인 확인 후 변경
        putPassword(t2, profileReauth, NEW_PW).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("REAUTH_REQUIRED"));
        putPassword(t2, reauth(t2, PW, "PASSWORD_CHANGE"), NEW_PW)
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("PASSWORD_CHANGED"));

        // 7. 옛 비밀번호는 로그인·본인 확인 모두 실패, 새 비밀번호는 성공
        login("moved@example.test", PW).andExpect(status().isUnauthorized());
        verifyPassword(t2, PW, "PROFILE_UPDATE").andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("PASSWORD_MISMATCH"));
        String t3 = loginBearer("moved@example.test", NEW_PW);
        reauth(t3, NEW_PW, "PROFILE_UPDATE");

        // 8. 의도된 취약점: 비밀번호를 바꿔도, 로그아웃해도 이전 토큰은 exp까지 그대로 동작
        mvc.perform(get("/mypage").header("Authorization", t1)).andExpect(status().isOk());
        mvc.perform(post("/logout").header("Authorization", t3)).andExpect(status().isNoContent());
        mvc.perform(get("/mypage").header("Authorization", t3)).andExpect(status().isOk());
    }

    @Test @DisplayName("두 회원 사이: 닉네임·이메일 중복이 가입과 프로필 수정 양쪽에서 맞물리고, 남의 토큰으로 남을 못 바꿈")
    void twoMembers() throws Exception {
        long a = signup("a@example.test", "에이회원");
        long b = signup("b@example.test", "비회원");
        String ta = loginBearer("a@example.test", PW);
        String tb = loginBearer("b@example.test", PW);

        // A가 이메일·닉네임을 바꾸면
        putProfile(ta, reauth(ta, PW, "PROFILE_UPDATE"), "바뀐에이", "a2@example.test").andExpect(status().isOk());

        // B는 A의 새 값으로 바꿀 수 없음
        String rb = reauth(tb, PW, "PROFILE_UPDATE");
        putProfile(tb, rb, "바뀐에이", "b@example.test").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_NICKNAME"));
        putProfile(tb, rb, "비회원", "a2@example.test").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_EMAIL"));
        // A의 옛 닉네임은 이제 비어 있어 B가 쓸 수 있음
        putProfile(tb, rb, "에이회원", "b@example.test").andExpect(status().isOk());

        // 가입 쪽도 같은 users를 봄: A의 새 이메일은 409, A의 옛 이메일은 다시 가입 가능
        mvc.perform(post("/email-auth").contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"a2@example.test\"}")
                        .with(r -> { r.setRemoteAddr("10.9.1.1"); return r; }))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_EMAIL"));
        signup("a@example.test", "새가입자");

        // B의 reauth 토큰을 A의 로그인 토큰과 섞으면 403 (토큰 sub로만 대상이 정해짐)
        putProfile(ta, rb, "가로채기", "a2@example.test").andExpect(status().isForbidden());
        putPassword(ta, reauth(tb, PW, "PASSWORD_CHANGE"), NEW_PW).andExpect(status().isForbidden());

        // A의 사진 업로드는 A 파일만 바꿈
        mvc.perform(multipart("/mypage/edit_prof").file(new MockMultipartFile("image", "x.png", "image/png", png()))
                .header("Authorization", ta)).andExpect(status().isOk());
        mvc.perform(get("/mypage").header("Authorization", ta))
                .andExpect(jsonPath("$.result.profileImageUrl", startsWith("/images/profile/" + a + "_profile.png")));
        mvc.perform(get("/mypage").header("Authorization", tb))
                .andExpect(jsonPath("$.result.profileImageUrl").value(nullValue()))
                .andExpect(jsonPath("$.result.memberId").value(b));
    }
}
