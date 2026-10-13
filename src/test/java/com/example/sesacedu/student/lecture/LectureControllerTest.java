package com.example.sesacedu.student.lecture;

import com.example.sesacedu.global.auth.*;
import com.example.sesacedu.global.exception.*;
import com.example.sesacedu.student.lecture.*;
import com.example.sesacedu.student.lecture.LectureRepository;
import com.example.sesacedu.student.lecture.LectureService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.List;
import java.util.Optional;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class LectureControllerTest {
    LectureRepository repository;
    MockMvc mvc;
    DefaultListableBeanFactory beans;

    @BeforeEach
    void setUp() {
        repository = mock(LectureRepository.class);
        beans = new DefaultListableBeanFactory();
        mvc = MockMvcBuilders.standaloneSetup(new LectureController(new LectureService(repository, new LectureFileStorage("./storage")), new FileResponses()))
                .setCustomArgumentResolvers(new CurrentUserArgumentResolver(beans.getBeanProvider(AccessTokenVerifier.class)))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void defaultListHasStableJsonContract() throws Exception {
        when(repository.countOpenLectures(null, null, null)).thenReturn(1);
        when(repository.findOpenLectures(null, null, null, "l.Lid DESC", 0, 12))
                .thenReturn(List.of(new LectureCard(12, "보안", null, "/thumb.png", "강사", "웹 보안", "입문", 49000, 4.5, 2)));
        mvc.perform(get("/lectures")).andExpect(status().isOk())
                .andExpect(jsonPath("$.lectures[0].lid").value(12))
                .andExpect(jsonPath("$.lectures[0].rating").value(4.5))
                .andExpect(jsonPath("$.lectures[0].reviewCount").value(2))
                .andExpect(jsonPath("$.page.current").value(1))
                .andExpect(jsonPath("$.page.size").value(12));
    }

    @Test
    void filtersAndPriceSortReachRepository() throws Exception {
        when(repository.countOpenLectures("보안", "웹 보안", "입문")).thenReturn(14);
        when(repository.findOpenLectures("보안", "웹 보안", "입문", "l.Lprice ASC, l.Lid DESC", 12, 12)).thenReturn(List.of());
        mvc.perform(get("/lectures").param("keyword", " 보안 ").param("category", "웹 보안")
                .param("level", "입문").param("sort", "price_asc").param("page", "2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.page.totalPages").value(2));
        verify(repository).findOpenLectures("보안", "웹 보안", "입문", "l.Lprice ASC, l.Lid DESC", 12, 12);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/lectures?page=0", "/lectures?page=abc", "/lectures?size=51", "/lectures?sort=hack", "/lectures/0", "/lectures/abc"})
    void badInputReturnsJson400(String url) throws Exception {
        mvc.perform(get(url)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").exists());
        verifyNoInteractions(repository);
    }

    @Test
    void missingLectureReturns404() throws Exception {
        when(repository.findOpenById(99)).thenReturn(Optional.empty());
        mvc.perform(get("/lectures/99")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LECTURE_NOT_FOUND"));
    }

    private void detailData() {
        when(repository.findOpenById(12)).thenReturn(Optional.of(new LectureDetail(12, 7, "보안", null,
                "소개", "웹 보안", "입문", "/thumb.png", null, 49000, "강사", "약력", "보안", "경력")));
        when(repository.findReviewSummary(12)).thenReturn(new ReviewSummary(null, 0));
        when(repository.findOtherOpenLectures(7, 12)).thenReturn(List.of());
    }

    @Test
    void guestCannotSupplyUserIdOrReceiveProtectedFields() throws Exception {
        detailData();
        mvc.perform(get("/lectures/12").param("user_id", "3")).andExpect(status().isOk())
                .andExpect(jsonPath("$.viewerState").value("guest"))
                .andExpect(jsonPath("$.price").value(49000))
                .andExpect(jsonPath("$.learnUrl").doesNotExist())
                .andExpect(jsonPath("$.video").doesNotExist())
                .andExpect(jsonPath("$.material").doesNotExist())
                .andExpect(jsonPath("$.instructor.account").doesNotExist())
                .andExpect(jsonPath("$.token").doesNotExist())
                .andExpect(jsonPath("$.noticeCount").doesNotExist());
        verify(repository, never()).findEnrollment(anyInt(), anyInt());
    }

    @Test
    void verifiedUserGetsOwnEnrollmentOnly() throws Exception {
        detailData();
        beans.registerSingleton("verifier", (AccessTokenVerifier) token -> new CurrentUser(3));
        when(repository.findEnrollment(3, 12)).thenReturn(Optional.of(new EnrollmentProgress(false, 10)));
        mvc.perform(get("/lectures/12").header("Authorization", "Bearer test-only").param("user_id", "99"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.viewerState").value("enrolled"))
                .andExpect(jsonPath("$.price").doesNotExist()).andExpect(jsonPath("$.position").value(10));
        verify(repository).findEnrollment(3, 12);
    }

    @Test
    void suppliedTokenWithoutVerifierFailsClosed() throws Exception {
        mvc.perform(get("/lectures/12").header("Authorization", "Bearer test-only"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("AUTH_NOT_CONFIGURED"));
        verifyNoInteractions(repository);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Basic invalid", "Bearer ", "Bearer a b"})
    void malformedAuthorizationIs401(String header) throws Exception {
        mvc.perform(get("/lectures/12").header("Authorization", header)).andExpect(status().isUnauthorized());
        verifyNoInteractions(repository);
    }

    @Test
    void rejectedTokenIsNotDowngradedToGuest() throws Exception {
        beans.registerSingleton("verifier", (AccessTokenVerifier) token -> {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "인증 실패");
        });
        mvc.perform(get("/lectures/12").header("Authorization", "Bearer test-only"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(repository);
    }
}
