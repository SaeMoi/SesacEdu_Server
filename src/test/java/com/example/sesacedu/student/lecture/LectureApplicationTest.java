package com.example.sesacedu.student.lecture;

import com.example.sesacedu.student.lecture.LectureRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 실제 Spring 설정·빈 등록까지 검증. Oracle 저장소만 대체한다. */
@SpringBootTest
@AutoConfigureMockMvc
class LectureApplicationTest {
    @Autowired MockMvc mvc;
    @MockBean LectureRepository repository;

    @Test
    void publicListIsRoutedWithApplicationConfiguration() throws Exception {
        mvc.perform(get("/lectures")).andExpect(status().isOk())
                .andExpect(jsonPath("$.lectures").isEmpty())
                .andExpect(jsonPath("$.page.current").value(1));
    }

    @Test
    void realMvcConfigurationRegistersAuthResolver() throws Exception {
        // 인증 담당분의 JwtUtil 이 들어오면서 검증기가 연결됐다.
        // 예전에는 검증기가 없어 503 AUTH_NOT_CONFIGURED 였지만, 이제는
        // 토큰이 실제로 검증되므로 깨진 토큰은 401 이다.
        //
        // 이 테스트가 확인하는 것은 응답 코드가 아니라
        // "실제 MVC 설정에서 CurrentUser 해석기와 검증기가 연결돼 있다" 는 것이다.
        // 연결이 끊기면 토큰을 읽지 않아 guest 로 처리되고 404 가 나온다.
        mvc.perform(get("/lectures/12").header("Authorization", "Bearer test-only"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").exists());
    }

    // ───────── CORS ─────────
    // 프론트 개발 서버(5173)에서 바로 호출할 때 브라우저가 먼저 보내는 사전 요청(preflight)을 본다.

    @Test
    void 프론트_개발서버의_사전요청은_허용한다() throws Exception {
        mvc.perform(options("/lectures/1/reviews")
                        .header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "PUT")
                        .header("Access-Control-Request-Headers", "authorization,content-type,range"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"))
                .andExpect(header().string("Access-Control-Allow-Methods",
                        org.hamcrest.Matchers.containsString("PUT")));
    }

}
