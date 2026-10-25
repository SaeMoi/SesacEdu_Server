package com.example.sesacedu;

import com.jayway.jsonpath.JsonPath;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.util.DigestUtils;
import org.springframework.util.FileSystemUtils;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.test.context.ActiveProfiles;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Date;
import java.util.Objects;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * mypage 패키지의 profile / edit_prof / password 가 Notion 명세(프로필 수정, 프로필 사진 변경, 비밀번호 변경)대로
 * 응답하는지 확인. H2(Oracle 모드, application-test.yml + schema-test.sql)로 실행:
 *   gradlew test --tests com.example.sesacedu.MypageProfileApiTest
 * 업로드 폴더는 build/test-profile-images (매 테스트 전에 비움). 10MB 초과 413은 실제 Tomcat이 필요해 RANDOM_PORT로 띄운다.
 */
@SuppressWarnings("null") // VS Code(JDT) null 분석이 Spring/MockMvc API에 내는 경고 제외. 실행에는 영향 없음
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "JWT_SECRET=" + MypageProfileApiTest.SECRET,
        "app.upload.profile-dir=" + MypageProfileApiTest.UPLOAD_DIR})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MypageProfileApiTest {

    static final String SECRET = "mypage-profile-test-secret-at-least-32-bytes!!";
    static final String UPLOAD_DIR = "build/test-profile-images";
    static final String PW = "Passw0rd!";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired TestRestTemplate rest;
    @Autowired org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext webContext;

    long studentId;
    long otherId;

    @BeforeEach
    void setUp() throws Exception {
        FileSystemUtils.deleteRecursively(Paths.get(UPLOAD_DIR));
        jdbc.update("DELETE FROM users");
        String md5 = md5(PW);
        jdbc.update("INSERT INTO users (mail, pw, uname, nickname, phone, b_date, address, auth, created) "
                        + "VALUES (?, ?, ?, ?, ?, DATE '2000-03-15', ?, 1, TIMESTAMP '2026-10-01 09:00:00')",
                "student@example.test", md5, "김새싹", "새싹보안", "01012345678", "서울시 어딘가");
        jdbc.update("INSERT INTO users (mail, pw, uname, nickname, auth) VALUES (?, ?, ?, ?, 0)",
                "other@example.test", md5, "다른이", "남의닉네임");
        studentId = Objects.requireNonNull(jdbc.queryForObject("SELECT user_id FROM users WHERE mail = 'student@example.test'", Long.class));
        otherId = Objects.requireNonNull(jdbc.queryForObject("SELECT user_id FROM users WHERE mail = 'other@example.test'", Long.class));
    }

    static String md5(String s) {
        return DigestUtils.md5DigestAsHex(s.getBytes(StandardCharsets.UTF_8));
    }

    /** 로그인 토큰. secondsAgo > 0 이면 이미 만료 */
    static String token(long sub, long secondsAgo) {
        return Jwts.builder().subject(String.valueOf(sub)).claim("role", "STUDENT").claim("reissue_num", 2)
                .expiration(new Date(System.currentTimeMillis() - secondsAgo * 1000))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)), Jwts.SIG.HS256).compact();
    }

    static String bearer(long sub) {
        return "Bearer " + token(sub, -3600);
    }

    /** POST /mypage/verify-password로 실제 발급받은 reauth 토큰 */
    String reauth(long sub, String purpose) throws Exception {
        MvcResult r = mvc.perform(post("/mypage/verify-password").header("Authorization", bearer(sub))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pw\":\"" + PW + "\",\"purpose\":\"" + purpose + "\"}"))
                .andExpect(status().isOk()).andReturn();
        return JsonPath.read(r.getResponse().getContentAsString(), "$.result.reauthToken");
    }

    static String profileJson(String name, String nickname, String email, String phone, String birthDate) {
        return "{" + field("name", name) + "," + field("nickname", nickname) + "," + field("email", email) + ","
                + field("phone", phone) + "," + field("birthDate", birthDate) + "}";
    }

    static String field(String k, String v) {
        return "\"" + k + "\":" + (v == null ? "null" : "\"" + v + "\"");
    }

    MockHttpServletRequestBuilder putProfile(long sub, String reauth, String json) {
        MockHttpServletRequestBuilder b = put("/mypage/profile").header("Authorization", bearer(sub))
                .contentType(MediaType.APPLICATION_JSON).content(json);
        return reauth == null ? b : b.header("X-Reauth-Token", reauth);
    }

    MockHttpServletRequestBuilder putPassword(long sub, String reauth, String newPw, String check) {
        MockHttpServletRequestBuilder b = put("/mypage/password").header("Authorization", bearer(sub))
                .contentType(MediaType.APPLICATION_JSON).content("{" + field("newPw", newPw) + "," + field("newPwCheck", check) + "}");
        return reauth == null ? b : b.header("X-Reauth-Token", reauth);
    }

    // ------------------------------------------------------------ PUT /mypage/profile

    @Test @DisplayName("프로필 수정 200: 공백 제거·이메일 소문자, GET /mypage와 같은 result, 허용 안 한 필드(role·auth·memberId) 무시")
    void profileSuccess() throws Exception {
        String json = "{\"name\":\" 김수정 \",\"nickname\":\" 새싹해커 \",\"email\":\" New@Example.TEST \","
                + "\"phone\":\"01099998888\",\"birthDate\":\"1999-12-31\",\"memberId\":" + otherId
                + ",\"role\":\"ADMIN\",\"auth\":3,\"status\":\"restricted\"}";
        mvc.perform(putProfile(studentId, reauth(studentId, "PROFILE_UPDATE"), json))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("PROFILE_UPDATED"))
                .andExpect(jsonPath("$.message").value("프로필이 수정되었습니다."))
                .andExpect(jsonPath("$.result.memberId").value(studentId))
                .andExpect(jsonPath("$.result.name").value("김수정"))
                .andExpect(jsonPath("$.result.nickname").value("새싹해커"))
                .andExpect(jsonPath("$.result.email").value("new@example.test"))
                .andExpect(jsonPath("$.result.phone").value("01099998888"))
                .andExpect(jsonPath("$.result.birthDate").value("1999-12-31"))
                .andExpect(jsonPath("$.result.profileImageUrl").value(nullValue()))
                .andExpect(jsonPath("$.result.role").value("STUDENT"))
                .andExpect(jsonPath("$.result.createdAt", matchesPattern("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\+09:00")))
                .andExpect(jsonPath("$.result.length()").value(9));
        assertEquals(1, jdbc.queryForObject("SELECT auth FROM users WHERE user_id = ?", Integer.class, studentId));
        assertEquals("active", jdbc.queryForObject("SELECT status FROM users WHERE user_id = ?", String.class, studentId));
        assertEquals("다른이", jdbc.queryForObject("SELECT uname FROM users WHERE user_id = ?", String.class, otherId));
        // GET /mypage도 바뀐 값
        mvc.perform(get("/mypage").header("Authorization", bearer(studentId)))
                .andExpect(jsonPath("$.result.email").value("new@example.test"))
                .andExpect(jsonPath("$.result.nickname").value("새싹해커"));
    }

    @Test @DisplayName("프로필 수정 200: 본인 현재 닉네임·이메일 그대로 저장 가능, reauth 토큰 10분 안에 재사용 가능 (의도된 취약점)")
    void profileKeepOwnValuesAndReuseToken() throws Exception {
        String reauth = reauth(studentId, "PROFILE_UPDATE");
        String json = profileJson("김새싹", "새싹보안", "student@example.test", "01012345678", "2000-03-15");
        mvc.perform(putProfile(studentId, reauth, json)).andExpect(status().isOk());
        mvc.perform(putProfile(studentId, reauth, json)).andExpect(status().isOk());
    }

    @Test @DisplayName("프로필 수정 400 INVALID_INPUT: 필드별 오류, 깨진 JSON, 빈 본문")
    void profileInvalidInput() throws Exception {
        String reauth = reauth(studentId, "PROFILE_UPDATE");
        mvc.perform(putProfile(studentId, reauth, profileJson(null, "a", "bad-mail", "010-1234-5678", "2000-02-30")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.message").value("입력값을 확인해주세요."))
                .andExpect(jsonPath("$.result.errors[*].field", contains("name", "nickname", "email", "phone", "birthDate")))
                .andExpect(jsonPath("$.result.errors[1].reason").value("2~20자로 입력해주세요."));
        mvc.perform(putProfile(studentId, reauth, profileJson("가".repeat(11), "가".repeat(21), "a".repeat(95) + "@x.com",
                        "123456789", "2999-01-01")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.result.errors[*].field", contains("name", "nickname", "email", "phone", "birthDate")))
                .andExpect(jsonPath("$.result.errors[4].reason").value("미래 날짜는 입력할 수 없습니다."));
        mvc.perform(putProfile(studentId, reauth, "{broken"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.result.errors[0].field").value("body"));
        mvc.perform(put("/mypage/profile").header("Authorization", bearer(studentId)).header("X-Reauth-Token", reauth))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.result.errors.length()").value(5));
    }

    @Test @DisplayName("프로필 수정 409: 다른 회원의 닉네임 DUPLICATE_NICKNAME, 이메일 DUPLICATE_EMAIL (대소문자 무시), DB 변경 없음")
    void profileDuplicate() throws Exception {
        String reauth = reauth(studentId, "PROFILE_UPDATE");
        mvc.perform(putProfile(studentId, reauth, profileJson("김새싹", "남의닉네임", "student@example.test", "01012345678", "2000-03-15")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_NICKNAME"))
                .andExpect(jsonPath("$.message").value("이미 사용 중인 닉네임입니다."))
                .andExpect(jsonPath("$.result").value(nullValue()));
        mvc.perform(putProfile(studentId, reauth, profileJson("김새싹", "새싹보안", "OTHER@example.test", "01012345678", "2000-03-15")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_EMAIL"))
                .andExpect(jsonPath("$.message").value("이미 사용 중인 이메일입니다."))
                .andExpect(jsonPath("$.result").value(nullValue()));
        assertEquals("새싹보안", jdbc.queryForObject("SELECT nickname FROM users WHERE user_id = ?", String.class, studentId));
    }

    @Test @DisplayName("프로필 수정 403 REAUTH_REQUIRED: 헤더 없음·위조·다른 용도·다른 회원·로그인 토큰")
    void profileReauthRequired() throws Exception {
        String json = profileJson("김새싹", "새싹보안", "student@example.test", "01012345678", "2000-03-15");
        for (String bad : new String[]{null, "garbage", reauth(studentId, "PASSWORD_CHANGE"),
                reauth(otherId, "PROFILE_UPDATE"), token(studentId, -3600)}) {
            mvc.perform(putProfile(studentId, bad, json))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("REAUTH_REQUIRED"))
                    .andExpect(jsonPath("$.result").value(nullValue()));
        }
        // 본인 확인이 입력 검증보다 먼저 (깨진 JSON이어도 403)
        mvc.perform(putProfile(studentId, null, "{broken")).andExpect(status().isForbidden());
    }

    @Test @DisplayName("프로필 수정 401: 토큰 없음 UNAUTHORIZED (깨진 JSON이어도), 만료 TOKEN_EXPIRED, 없는 회원 UNAUTHORIZED")
    void profileUnauthorized() throws Exception {
        String reauth = reauth(studentId, "PROFILE_UPDATE");
        String json = profileJson("김새싹", "새싹보안", "student@example.test", "01012345678", "2000-03-15");
        mvc.perform(put("/mypage/profile").header("X-Reauth-Token", reauth).contentType(MediaType.APPLICATION_JSON).content("{broken"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mvc.perform(put("/mypage/profile").header("Authorization", "Bearer " + token(studentId, 60)).header("X-Reauth-Token", reauth)
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TOKEN_EXPIRED"));
        jdbc.update("DELETE FROM users WHERE user_id = ?", studentId);
        mvc.perform(putProfile(studentId, reauth, json))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    // ------------------------------------------------------------ POST /mypage/edit_prof

    static byte[] image(String format, int w, int h) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = img.createGraphics();
        g.setColor(Color.ORANGE);
        g.fillRect(0, 0, w, h);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(img, format, out));
        return out.toByteArray();
    }

    MockMultipartHttpServletRequestBuilder upload(long sub, MockMultipartFile... files) {
        MockMultipartHttpServletRequestBuilder b = multipart("/mypage/edit_prof");
        for (MockMultipartFile f : files) b.file(f);
        b.header("Authorization", bearer(sub));
        return b;
    }

    static MockMultipartFile part(byte[] bytes, String filename, String contentType) {
        return new MockMultipartFile("image", filename, contentType, bytes);
    }

    @Test @DisplayName("사진 변경 200: [의도된 취약점] 원본 바이트를 클라이언트 파일명·확장자 그대로 저장(재인코딩 없음, 경로우회만 차단), DB에 그 이름, GET /mypage 같은 URL")
    void uploadSuccess() throws Exception {
        for (String fmt : new String[]{"jpg", "png"}) {
            byte[] uploaded = image(fmt, 800, 600);
            MvcResult r = mvc.perform(upload(studentId, part(uploaded, "evil." + fmt, "image/" + fmt)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value("PROFILE_IMAGE_UPDATED"))
                    .andExpect(jsonPath("$.message").value("프로필 사진이 변경되었습니다."))
                    .andExpect(jsonPath("$.result.profileImageUrl",
                            matchesPattern("/images/profile/evil\\." + fmt + "(\\?v=\\d+)?")))
                    .andReturn();
            String url = JsonPath.read(r.getResponse().getContentAsString(), "$.result.profileImageUrl");
            // 재인코딩 없이 업로드 원본을 클라이언트 파일명 그대로 저장
            Path saved = Paths.get(UPLOAD_DIR, "evil." + fmt);
            assertTrue(Files.exists(saved));
            assertArrayEquals(uploaded, Files.readAllBytes(saved));
            assertEquals("evil." + fmt,
                    jdbc.queryForObject("SELECT picture FROM users WHERE user_id = ?", String.class, studentId));
            mvc.perform(get("/mypage").header("Authorization", bearer(studentId)))
                    .andExpect(jsonPath("$.result.profileImageUrl").value(url));
        }
    }

    @Test @DisplayName("사진 변경: [의도된 취약점] 이미지 폴리글랏(헤더+스크립트)이 재인코딩 없이 그대로 저장·서빙된다 = 웹셸 업로드")
    void uploadWebshellPolyglot() throws Exception {
        byte[] img = image("png", 100, 100);
        byte[] payload = "<% out.println(\"PWNED\"); %>".getBytes(StandardCharsets.UTF_8);
        byte[] polyglot = Arrays.copyOf(img, img.length + payload.length);
        System.arraycopy(payload, 0, polyglot, img.length, payload.length);
        // PNG 매직바이트로 형식검사 통과, shell.jsp 이름으로 업로드
        MvcResult r = mvc.perform(upload(studentId, part(polyglot, "shell.jsp", "image/png")))
                .andExpect(status().isOk()).andReturn();
        String url = JsonPath.read(r.getResponse().getContentAsString(), "$.result.profileImageUrl");
        assertTrue(url.startsWith("/images/profile/shell.jsp"), "저장 경로가 공격자 파일명 그대로");
        // 디스크에 payload 보존 (재인코딩으로 사라지지 않음)
        byte[] stored = Files.readAllBytes(Paths.get(UPLOAD_DIR, "shell.jsp"));
        assertTrue(new String(stored, StandardCharsets.ISO_8859_1).contains("out.println(\"PWNED\")"));
        // 비인증으로 그대로 서빙됨 (바이트 보존)
        byte[] served = mvc.perform(get("/images/profile/shell.jsp")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertArrayEquals(stored, served);
    }

    @Test @DisplayName("사진 변경: [의도된 취약점 A01 Path Traversal] ../ 파일명이 images 폴더 밖으로 기록된다 (write-anywhere)")
    void uploadPathTraversalEscapesDir() throws Exception {
        byte[] img = image("png", 60, 60);
        byte[] payload = "<% /* shell */ %>".getBytes(StandardCharsets.UTF_8);
        byte[] polyglot = Arrays.copyOf(img, img.length + payload.length);
        System.arraycopy(payload, 0, polyglot, img.length, payload.length);
        // UPLOAD_DIR 의 부모(build/) 아래 전용 하위폴더로만 탈출시켜 테스트 샌드박스 안에 둔다 (실제 시스템 경로로 쓰지 않음)
        String sub = "esc-" + java.util.UUID.randomUUID();
        Path profileDir = Paths.get(UPLOAD_DIR).toAbsolutePath().normalize();
        Path escaped = profileDir.resolve("../" + sub + "/shell.jsp").normalize();   // = build/esc-xxx/shell.jsp
        try {
            mvc.perform(upload(studentId, part(polyglot, "../" + sub + "/shell.jsp", "image/png")))
                    .andExpect(status().isOk());
            assertTrue(Files.exists(escaped), "탈출 경로에 파일이 생겨야 함: " + escaped);
            assertFalse(escaped.startsWith(profileDir), "프로필 폴더 밖이어야 함");
            assertArrayEquals(polyglot, Files.readAllBytes(escaped));   // 재인코딩 없이 원본 보존
        } finally {
            Files.deleteIfExists(escaped);
            Files.deleteIfExists(escaped.getParent());
        }
    }

    @Test @DisplayName("사진 변경 400 INVALID_INPUT: image 없음, 빈 파일, 2개, multipart 아님")
    void uploadInvalidInput() throws Exception {
        mvc.perform(upload(studentId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.result.errors[0].field").value("image"));
        mvc.perform(upload(studentId, part(new byte[0], "a.png", "image/png")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        mvc.perform(upload(studentId, part(image("png", 10, 10), "a.png", "image/png"), part(image("png", 10, 10), "b.png", "image/png")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.result.errors[0].reason").value("이미지는 1개만 올릴 수 있습니다."));
        mvc.perform(post("/mypage/edit_prof").header("Authorization", bearer(studentId))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    @Test @DisplayName("사진 변경 413 FILE_TOO_LARGE {maxBytes}: 5MB 초과 (MockMvc)")
    void uploadTooLarge() throws Exception {
        byte[] big = new byte[5 * 1024 * 1024 + 1];
        big[0] = (byte) 0x89; big[1] = 'P'; big[2] = 'N'; big[3] = 'G';
        mvc.perform(upload(studentId, part(big, "big.png", "image/png")))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("FILE_TOO_LARGE"))
                .andExpect(jsonPath("$.message").value("5MB 이하의 이미지만 올릴 수 있습니다."))
                .andExpect(jsonPath("$.result.maxBytes").value(5242880));
    }

    @Test @DisplayName("사진 변경 413: 실제 서버에서 멀티파트 한도(10MB)를 넘어도 JSON 413")
    void uploadOverMultipartLimit() {
        HttpHeaders h = new HttpHeaders();
        h.set("Authorization", bearer(studentId));
        h.setContentType(MediaType.MULTIPART_FORM_DATA);
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("image", new ByteArrayResource(new byte[10 * 1024 * 1024 + 1]) {
            @Override public String getFilename() { return "huge.png"; }
        });
        // Tomcat이 남은 본문을 읽고 응답해야 Windows에서도 연결이 끊기지 않는다 (ProfileImageConfig)
        org.apache.catalina.Context ctx = (org.apache.catalina.Context) ((org.springframework.boot.web.embedded.tomcat.TomcatWebServer)
                webContext.getWebServer()).getTomcat().getHost().findChildren()[0];
        assertTrue(ctx.getSwallowAbortedUploads());
        ResponseEntity<String> res = rest.postForEntity("/mypage/edit_prof", new HttpEntity<>(form, h), String.class);
        assertEquals(413, res.getStatusCodeValue());
        assertEquals("FILE_TOO_LARGE", JsonPath.read(res.getBody(), "$.code"));
    }

    @Test @DisplayName("사진 변경 415 UNSUPPORTED_IMAGE_TYPE: GIF·SVG·HTML (Content-Type·확장자를 속여도 내용으로 판단)")
    void uploadUnsupported() throws Exception {
        byte[][] bodies = {image("gif", 10, 10), "<svg xmlns='http://www.w3.org/2000/svg'/>".getBytes(StandardCharsets.UTF_8),
                "<html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8)};
        for (byte[] b : bodies) {
            mvc.perform(upload(studentId, part(b, "fake.png", "image/png")))
                    .andExpect(status().isUnsupportedMediaType())
                    .andExpect(jsonPath("$.code").value("UNSUPPORTED_IMAGE_TYPE"))
                    .andExpect(jsonPath("$.message").value("JPG, PNG 이미지만 올릴 수 있습니다."))
                    .andExpect(jsonPath("$.result").value(nullValue()));
        }
    }

    @Test @DisplayName("사진 변경 400 INVALID_IMAGE: 매직 바이트만 맞는 깨진 파일, 4,096px 초과, DB·파일 변경 없음")
    void uploadInvalidImage() throws Exception {
        byte[] broken = Arrays.copyOf(image("png", 50, 50), 30);
        byte[] fakeJpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1, 2, 3, 4, 5};
        for (byte[] b : new byte[][]{broken, fakeJpeg, image("png", 4097, 1)}) {
            mvc.perform(upload(studentId, part(b, "a.png", "image/png")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_IMAGE"))
                    .andExpect(jsonPath("$.message").value("이미지 파일을 읽을 수 없습니다."))
                    .andExpect(jsonPath("$.result").value(nullValue()));
        }
        assertNull(jdbc.queryForObject("SELECT picture FROM users WHERE user_id = ?", String.class, studentId));
        assertFalse(Files.exists(Paths.get(UPLOAD_DIR, studentId + "_profile.png")));
        // 4,096px 정확히는 허용
        mvc.perform(upload(studentId, part(image("png", 4096, 1), "a.png", "image/png"))).andExpect(status().isOk());
    }

    @Test @DisplayName("사진 변경 429 TOO_MANY_REQUESTS {retryAfter}: 회원당 1시간 10회, 다른 회원은 영향 없음")
    void uploadRateLimit() throws Exception {
        byte[] png = image("png", 20, 20);
        for (int i = 0; i < 10; i++) {
            mvc.perform(upload(studentId, part(png, "a.png", "image/png"))).andExpect(status().isOk());
        }
        mvc.perform(upload(studentId, part(png, "a.png", "image/png")))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("TOO_MANY_REQUESTS"))
                .andExpect(jsonPath("$.result.retryAfter", allOf(greaterThan(3500), lessThanOrEqualTo(3600))));
        mvc.perform(upload(otherId, part(png, "a.png", "image/png"))).andExpect(status().isOk());
    }

    @Test @DisplayName("사진 변경 401: 토큰 없음 UNAUTHORIZED, 만료 TOKEN_EXPIRED, 없는 회원 UNAUTHORIZED")
    void uploadUnauthorized() throws Exception {
        MockMultipartFile f = part(image("png", 10, 10), "a.png", "image/png");
        mvc.perform(multipart("/mypage/edit_prof").file(f))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mvc.perform(multipart("/mypage/edit_prof").file(f).header("Authorization", "Bearer " + token(studentId, 60)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TOKEN_EXPIRED"));
        mvc.perform(upload(999999, f))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test @DisplayName("GET /images/profile/{파일명}: 인증 없이 서빙, nosniff·inline·max-age. [의도된 취약점] 화이트리스트 제거로 임의 확장자(.txt 등)도 서빙됨(404 아님)")
    void serveImage() throws Exception {
        mvc.perform(upload(studentId, part(image("png", 50, 50), "a.png", "image/png"))).andExpect(status().isOk());
        MvcResult r = mvc.perform(get("/images/profile/a.png"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Content-Disposition", "inline"))
                .andExpect(header().string("Cache-Control", containsString("max-age=86400")))
                .andReturn();
        assertNotNull(ImageIO.read(new ByteArrayInputStream(r.getResponse().getContentAsByteArray())));
        // [의도된 취약점] 비이미지 확장자도 그대로 서빙 (원래는 ^[0-9]+_profile\.png$ 외 404 였다)
        Files.write(Paths.get(UPLOAD_DIR, "secret.txt"), "topsecret".getBytes(StandardCharsets.UTF_8));
        mvc.perform(get("/images/profile/secret.txt"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("topsecret")));
        // 없는 파일은 여전히 404
        mvc.perform(get("/images/profile/999999_profile.png")).andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------ PUT /mypage/password

    @Test @DisplayName("비밀번호 변경 200: MD5로 저장, result null, no-store, 기존 로그인 토큰은 계속 유효 (의도된 취약점)")
    void passwordSuccess() throws Exception {
        mvc.perform(putPassword(studentId, reauth(studentId, "PASSWORD_CHANGE"), "N3wPassw0rd!", "N3wPassw0rd!"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("PASSWORD_CHANGED"))
                .andExpect(jsonPath("$.message").value("비밀번호가 변경되었습니다. 다시 로그인해주세요."))
                .andExpect(jsonPath("$.result").value(nullValue()));
        assertEquals(md5("N3wPassw0rd!"), jdbc.queryForObject("SELECT pw FROM users WHERE user_id = ?", String.class, studentId));
        assertEquals(md5(PW), jdbc.queryForObject("SELECT pw FROM users WHERE user_id = ?", String.class, otherId));
        mvc.perform(get("/mypage").header("Authorization", bearer(studentId))).andExpect(status().isOk());
    }

    @Test @DisplayName("비밀번호 변경: reauth 토큰 재사용 가능 (1회용 아님, 의도된 취약점)")
    void passwordReuseToken() throws Exception {
        String reauth = reauth(studentId, "PASSWORD_CHANGE");
        mvc.perform(putPassword(studentId, reauth, "N3wPassw0rd!", "N3wPassw0rd!")).andExpect(status().isOk());
        mvc.perform(putPassword(studentId, reauth, "An0ther#Pass", "An0ther#Pass")).andExpect(status().isOk());
        assertEquals(md5("An0ther#Pass"), jdbc.queryForObject("SELECT pw FROM users WHERE user_id = ?", String.class, studentId));
    }

    @Test @DisplayName("비밀번호 변경 400 SAME_AS_CURRENT_PASSWORD")
    void passwordSame() throws Exception {
        mvc.perform(putPassword(studentId, reauth(studentId, "PASSWORD_CHANGE"), PW, PW))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SAME_AS_CURRENT_PASSWORD"))
                .andExpect(jsonPath("$.message").value("현재 비밀번호와 다른 비밀번호를 입력해주세요."))
                .andExpect(jsonPath("$.result").value(nullValue()));
    }

    @Test @DisplayName("비밀번호 변경 400 INVALID_INPUT: 누락, 규칙 위반(짧음·특수문자 없음·31자), 확인 불일치, 깨진 JSON")
    void passwordInvalidInput() throws Exception {
        String reauth = reauth(studentId, "PASSWORD_CHANGE");
        mvc.perform(putPassword(studentId, reauth, null, null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.result.errors[*].field", contains("newPw", "newPwCheck")));
        for (String bad : new String[]{"Ab1!", "Password123", "Ab1!" + "a".repeat(27)}) {
            mvc.perform(putPassword(studentId, reauth, bad, bad))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.result.errors[0].field").value("newPw"));
        }
        mvc.perform(putPassword(studentId, reauth, "N3wPassw0rd!", "N3wPassw0rd?"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.result.errors[0].field").value("newPwCheck"))
                .andExpect(jsonPath("$.result.errors[0].reason").value("새 비밀번호가 일치하지 않습니다."));
        mvc.perform(put("/mypage/password").header("Authorization", bearer(studentId)).header("X-Reauth-Token", reauth)
                        .contentType(MediaType.APPLICATION_JSON).content("{broken"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.result.errors[0].field").value("body"));
        assertEquals(md5(PW), jdbc.queryForObject("SELECT pw FROM users WHERE user_id = ?", String.class, studentId));
    }

    @Test @DisplayName("비밀번호 변경 403 REAUTH_REQUIRED: 없음·PROFILE_UPDATE 용도·다른 회원 토큰")
    void passwordReauthRequired() throws Exception {
        for (String bad : new String[]{null, reauth(studentId, "PROFILE_UPDATE"), reauth(otherId, "PASSWORD_CHANGE")}) {
            mvc.perform(putPassword(studentId, bad, "N3wPassw0rd!", "N3wPassw0rd!"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("REAUTH_REQUIRED"))
                    .andExpect(jsonPath("$.result").value(nullValue()));
        }
        assertEquals(md5(PW), jdbc.queryForObject("SELECT pw FROM users WHERE user_id = ?", String.class, studentId));
    }

    @Test @DisplayName("비밀번호 변경 401: 토큰 없음 UNAUTHORIZED, 만료 TOKEN_EXPIRED, 없는 회원 UNAUTHORIZED")
    void passwordUnauthorized() throws Exception {
        String reauth = reauth(studentId, "PASSWORD_CHANGE");
        mvc.perform(put("/mypage/password").header("X-Reauth-Token", reauth).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newPw\":\"N3wPassw0rd!\",\"newPwCheck\":\"N3wPassw0rd!\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mvc.perform(put("/mypage/password").header("Authorization", "Bearer " + token(studentId, 60)).header("X-Reauth-Token", reauth)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"newPw\":\"N3wPassw0rd!\",\"newPwCheck\":\"N3wPassw0rd!\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TOKEN_EXPIRED"));
        jdbc.update("DELETE FROM users WHERE user_id = ?", studentId);
        mvc.perform(putPassword(studentId, reauth, "N3wPassw0rd!", "N3wPassw0rd!"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }
}
