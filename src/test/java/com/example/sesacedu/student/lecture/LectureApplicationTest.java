package com.example.sesacedu.student.lecture;

import com.example.sesacedu.student.lecture.LectureRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
        mvc.perform(get("/lectures/12").header("Authorization", "Bearer test-only"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("AUTH_NOT_CONFIGURED"));
    }
}
