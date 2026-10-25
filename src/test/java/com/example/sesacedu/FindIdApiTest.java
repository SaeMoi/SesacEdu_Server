package com.example.sesacedu;

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
import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;
import java.sql.Date;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * auth 패키지의 find_id (POST /login/find-id, Notion 명세: 아이디 확인) 확인.
 * 응답 코드: 200 ACCOUNT_FOUND / 400 INVALID_INPUT / 404 ACCOUNT_NOT_FOUND.
 * 의도된 취약점(모의해킹용)도 테스트로 고정한다:
 *   - A01/A07: 이메일을 마스킹 없이 전체 반환 + 있음(200)/없음(404) 구분 = 계정 열거 + 개인정보 노출
 *   - A03:     name UNION SQL 인젝션으로 다른 컬럼(pw 해시)을 email 자리로 탈취
 * H2(Oracle 모드, application-test.yml + schema-test.sql):
 *   gradlew test --tests com.example.sesacedu.FindIdApiTest
 */
@SuppressWarnings("null") // VS Code(JDT) null 분석이 Spring/MockMvc API에 내는 경고 제외. 실행에는 영향 없음
@SpringBootTest(properties = "JWT_SECRET=" + FindIdApiTest.SECRET)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FindIdApiTest {

    static final String SECRET = "find-id-api-test-secret-at-least-32-bytes!!";
    static final String PW = "Passw0rd!";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM users");
        String md5 = DigestUtils.md5DigestAsHex(PW.getBytes(StandardCharsets.UTF_8));
        jdbc.update("INSERT INTO users (mail, pw, uname, nickname, auth, b_date) VALUES (?, ?, ?, ?, 1, ?)",
                "jade@example.com", md5, "김태형", "태형", Date.valueOf("2000-03-15"));
        jdbc.update("INSERT INTO users (mail, pw, uname, nickname, auth, b_date) VALUES (?, ?, ?, ?, 1, ?)",
                "other@example.com", md5, "홍길동", "길동", Date.valueOf("1995-07-08"));
    }

    static String json(String name, String birthDate) {
        return "{\"name\":" + (name == null ? "null" : "\"" + name + "\"")
                + ",\"birthDate\":" + (birthDate == null ? "null" : "\"" + birthDate + "\"") + "}";
    }

    @Test @DisplayName("아이디 확인 200 ACCOUNT_FOUND: 이름+생년월일 일치하면 이메일 반환")
    void findSuccess() throws Exception {
        mvc.perform(post("/login/find-id").contentType(MediaType.APPLICATION_JSON).content(json("김태형", "2000-03-15")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("ACCOUNT_FOUND"))
                .andExpect(jsonPath("$.message").value("가입된 이메일을 찾았습니다."))
                .andExpect(jsonPath("$.result.email").value("jade@example.com"));
    }

    @Test @DisplayName("아이디 확인 404 ACCOUNT_NOT_FOUND: 이름 또는 생년월일 불일치, result null")
    void notFound() throws Exception {
        for (String body : new String[]{json("없는이름", "2000-03-15"), json("김태형", "1999-01-01")}) {
            mvc.perform(post("/login/find-id").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"))
                    .andExpect(jsonPath("$.result").value(nullValue()));
        }
    }

    @Test @DisplayName("아이디 확인 400 INVALID_INPUT: 누락·날짜 형식 오류·실재하지 않는 날짜·깨진 JSON·본문 없음")
    void invalidInput() throws Exception {
        mvc.perform(post("/login/find-id").contentType(MediaType.APPLICATION_JSON).content(json(null, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.message").value("입력값을 확인해주세요."))
                .andExpect(jsonPath("$.result.errors[*].field", containsInAnyOrder("name", "birthDate")))
                .andExpect(jsonPath("$.result.errors[*].reason", everyItem(not(emptyString()))));
        mvc.perform(post("/login/find-id").contentType(MediaType.APPLICATION_JSON).content(json("김태형", "2000/03/15")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.result.errors[*].field", contains("birthDate")));
        mvc.perform(post("/login/find-id").contentType(MediaType.APPLICATION_JSON).content(json("김태형", "2026-02-30")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.result.errors[*].field", contains("birthDate")));
        mvc.perform(post("/login/find-id").contentType(MediaType.APPLICATION_JSON).content("{broken"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        mvc.perform(post("/login/find-id").contentType(MediaType.APPLICATION_JSON))   // 본문 없음
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    @Test @DisplayName("의도된 취약점(A01/A07): 이메일 마스킹 없이 전체 반환 + 있음(200)/없음(404) 구분 = 계정 열거")
    void enumerationAndPiiExposure() throws Exception {
        mvc.perform(post("/login/find-id").contentType(MediaType.APPLICATION_JSON).content(json("홍길동", "1995-07-08")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.email").value("other@example.com"))
                .andExpect(jsonPath("$.result.email", not(containsString("*"))));      // 마스킹 안 함
        mvc.perform(post("/login/find-id").contentType(MediaType.APPLICATION_JSON).content(json("홍길동", "1990-01-01")))
                .andExpect(status().isNotFound());                                      // 없는 조합은 404 → 열거 가능
    }

    @Test @DisplayName("의도된 취약점(A03): name UNION SQLi로 다른 컬럼(pw 해시)을 email로 탈취")
    void sqlInjectionOnName() throws Exception {
        String md5 = DigestUtils.md5DigestAsHex(PW.getBytes(StandardCharsets.UTF_8));
        // name은 50자 제한이 있으므로 WHERE 없는 간결한 UNION(31자). 시드 2명이 같은 md5(PW)라 첫 행 pw == md5.
        String payload = "x' UNION SELECT pw FROM users--";
        mvc.perform(post("/login/find-id").contentType(MediaType.APPLICATION_JSON).content(json(payload, "2000-03-15")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("ACCOUNT_FOUND"))
                .andExpect(jsonPath("$.result.email").value(md5));
    }
}
