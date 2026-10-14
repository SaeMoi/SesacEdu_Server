package com.example.sesacedu.student.lecture;

import com.example.sesacedu.global.auth.AccessTokenVerifier;
import com.example.sesacedu.global.auth.CurrentUser;
import com.example.sesacedu.global.auth.CurrentUserArgumentResolver;
import com.example.sesacedu.global.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 수강평 작성·수정·삭제와 Q&A 답변 상태 필터.
 *
 * 강의 1 은 강사 7 의 공개 강의, 사용자 10 은 수강자, 12 는 수강하지 않은 회원이다.
 */
class LectureBoardWriteTest {

    static final String TOKEN = "Bearer test-token";
    static final LocalDateTime WRITTEN = LocalDateTime.of(2026, 10, 14, 9, 0);

    LectureRepository lectureRepository;
    LectureBoardRepository boardRepository;
    DefaultListableBeanFactory beans;

    @BeforeEach
    void setUp() {
        lectureRepository = mock(LectureRepository.class);
        boardRepository = mock(LectureBoardRepository.class);
        beans = new DefaultListableBeanFactory();

        when(lectureRepository.findOpenById(1)).thenReturn(Optional.of(mock(LectureDetail.class)));
        when(boardRepository.findInstructorUserId(1)).thenReturn(Optional.of(7));
        when(lectureRepository.findEnrollment(10, 1)).thenReturn(Optional.of(new EnrollmentProgress(false, 0)));
        when(lectureRepository.findEnrollment(12, 1)).thenReturn(Optional.empty());
    }

    MockMvc mvcAs(Integer userId) {
        // 한 테스트 안에서 여러 번 불려도 검증기는 한 번만 등록한다.
        if (userId != null && !beans.containsSingleton("verifier")) {
            beans.registerSingleton("verifier", (AccessTokenVerifier) token -> new CurrentUser(userId));
        }
        return MockMvcBuilders
                .standaloneSetup(new LectureBoardController(
                        new LectureBoardService(lectureRepository, boardRepository)))
                .setCustomArgumentResolvers(
                        new CurrentUserArgumentResolver(beans.getBeanProvider(AccessTokenVerifier.class)))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    String review(Object star, String text) {
        return "{\"star\":" + star + ",\"review\":\"" + text + "\"}";
    }

    // ───────── 목록의 mine ─────────

    void twoReviews() {
        when(lectureRepository.findReviewSummary(1)).thenReturn(new ReviewSummary(4.0, 2));
        when(boardRepository.countReviews(1)).thenReturn(2);
        when(boardRepository.findReviews(1, 0, 10)).thenReturn(List.of(
                new ReviewRow(10, "수강생A", 5, "좋습니다", WRITTEN),
                new ReviewRow(11, "수강생B", 3, "보통", WRITTEN)));
    }

    @Test
    void 목록은_작성자_번호를_준다() throws Exception {
        twoReviews();

        mvcAs(10).perform(get("/lectures/1/reviews").header("Authorization", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviews[0].userId").value(10))
                .andExpect(jsonPath("$.reviews[1].userId").value(11));
    }

    // ───────── 작성 ─────────

    @Test
    void 평점이_없으면_INVALID_STAR다() throws Exception {
        mvcAs(10).perform(post("/lectures/1/reviews").header("Authorization", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"review\":\"평점 없이\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_STAR"));
        verify(boardRepository, never()).insertReview(anyInt(), anyInt(), anyInt(), any());
    }

    @Test
    void 평점이_1에서_5를_벗어나면_INVALID_STAR다() throws Exception {
        for (int star : new int[]{0, 6, 100, -1}) {
            mvcAs(10).perform(post("/lectures/1/reviews").header("Authorization", TOKEN)
                            .contentType(MediaType.APPLICATION_JSON).content(review(star, "x")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_STAR"));
        }
        verify(boardRepository, never()).insertReview(anyInt(), anyInt(), anyInt(), any());
    }

    @Test
    void 본문이_너무_길면_평점이_아니라_INVALID_INPUT이다() throws Exception {
        String longText = String.join("", Collections.nCopies(2001, "가"));
        mvcAs(10).perform(post("/lectures/1/reviews").header("Authorization", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content(review(5, longText)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    @Test
    void 수강자는_리뷰를_쓸_수_있다() throws Exception {
        when(boardRepository.existsReview(1, 10)).thenReturn(false);

        mvcAs(10).perform(post("/lectures/1/reviews").header("Authorization", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content(review(5, "좋다")))
                .andExpect(status().isCreated());
        verify(boardRepository).insertReview(1, 10, 5, "좋다");
    }

    // ───────── 수정 ─────────

    @Test
    void 수정은_비로그인이면_401이다() throws Exception {
        mvcAs(null).perform(put("/lectures/1/reviews")
                        .contentType(MediaType.APPLICATION_JSON).content(review(4, "x")))
                .andExpect(status().isUnauthorized());
        verify(boardRepository, never()).updateReview(anyInt(), anyInt(), anyInt(), any());
    }

    @Test
    void 수정은_수강자가_아니면_403이다() throws Exception {
        mvcAs(12).perform(put("/lectures/1/reviews").header("Authorization", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content(review(4, "x")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_ENROLLED"));
        verify(boardRepository, never()).updateReview(anyInt(), anyInt(), anyInt(), any());
    }

    @Test
    void 수정도_평점_범위를_같은_규칙으로_본다() throws Exception {
        mvcAs(10).perform(put("/lectures/1/reviews").header("Authorization", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content(review(100, "x")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_STAR"));
        verify(boardRepository, never()).updateReview(anyInt(), anyInt(), anyInt(), any());
    }

    @Test
    void 쓴_적이_없으면_수정은_404다() throws Exception {
        when(boardRepository.updateReview(1, 10, 4, "수정")).thenReturn(0);

        mvcAs(10).perform(put("/lectures/1/reviews").header("Authorization", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content(review(4, "수정")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REVIEW_NOT_FOUND"));
    }

    @Test
    void 수정은_토큰의_사용자_리뷰에만_적용된다() throws Exception {
        when(boardRepository.updateReview(1, 10, 4, "수정")).thenReturn(1);

        mvcAs(10).perform(put("/lectures/1/reviews").header("Authorization", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"star\":4,\"review\":\"수정\",\"userId\":11}"))
                .andExpect(status().isOk());
        verify(boardRepository).updateReview(1, 10, 4, "수정");
        verify(boardRepository, never()).updateReview(1, 11, 4, "수정");
    }

    @Test
    void 공개되지_않은_강의의_리뷰는_고칠_수_없다() throws Exception {
        when(lectureRepository.findOpenById(5)).thenReturn(Optional.empty());

        mvcAs(10).perform(put("/lectures/5/reviews").header("Authorization", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content(review(4, "x")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LECTURE_NOT_FOUND"));
    }

    // ───────── 삭제 ─────────

    @Test
    void 내_리뷰를_지우면_204다() throws Exception {
        when(boardRepository.deleteReview(1, 10)).thenReturn(1);

        mvcAs(10).perform(delete("/lectures/1/reviews").header("Authorization", TOKEN))
                .andExpect(status().isNoContent());
        verify(boardRepository).deleteReview(1, 10);
    }

    @Test
    void 지울_리뷰가_없으면_404다() throws Exception {
        when(boardRepository.deleteReview(1, 10)).thenReturn(0);

        mvcAs(10).perform(delete("/lectures/1/reviews").header("Authorization", TOKEN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REVIEW_NOT_FOUND"));
    }

    @Test
    void 삭제는_수강자가_아니면_403이다() throws Exception {
        mvcAs(12).perform(delete("/lectures/1/reviews").header("Authorization", TOKEN))
                .andExpect(status().isForbidden());
        verify(boardRepository, never()).deleteReview(anyInt(), anyInt());
    }

    // ───────── Q&A 답변 상태 필터 ─────────

    @Test
    void 답변완료만_걸러_볼_수_있다() throws Exception {
        when(boardRepository.countQna(1, "answered")).thenReturn(1);
        when(boardRepository.findQna(1, 0, 10, "answered")).thenReturn(List.of(
                new QnaRow(3, 11, "수강생B", "공개 질문", null, null, 7, WRITTEN, false, WRITTEN)));

        mvcAs(null).perform(get("/lectures/1/qna").param("status", "answered"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.questions[0].qid").value(3))
                .andExpect(jsonPath("$.questions[0].answered").value(true))
                .andExpect(jsonPath("$.page.total").value(1));
    }

    @Test
    void 대문자로_보내도_같은_필터다() throws Exception {
        when(boardRepository.countQna(1, "waiting")).thenReturn(0);

        mvcAs(null).perform(get("/lectures/1/qna").param("status", "WAITING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.total").value(0));
        verify(boardRepository).countQna(1, "waiting");
    }

    @Test
    void 명세_초안의_all과_unanswered도_받는다() throws Exception {
        when(boardRepository.countQna(1, null)).thenReturn(0);
        when(boardRepository.countQna(1, "waiting")).thenReturn(0);

        mvcAs(null).perform(get("/lectures/1/qna").param("status", "all")).andExpect(status().isOk());
        mvcAs(null).perform(get("/lectures/1/qna").param("status", "unanswered")).andExpect(status().isOk());
        verify(boardRepository).countQna(1, null);
        verify(boardRepository).countQna(1, "waiting");
    }

    @Test
    void Q_A_상세는_작성자와_답변자_번호를_준다() throws Exception {
        when(boardRepository.findQnaDetail(1, 3)).thenReturn(Optional.of(
                new QnaRow(3, 10, "수강생A", "공개 질문", "본문", "답변", 7, WRITTEN, false, WRITTEN)));

        mvcAs(10).perform(get("/lectures/1/qna/3").header("Authorization", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(10))
                .andExpect(jsonPath("$.answeredBy").value(7));
    }

    @Test
    void 상태를_안_보내면_전체다() throws Exception {
        when(boardRepository.countQna(1, null)).thenReturn(0);

        mvcAs(null).perform(get("/lectures/1/qna"))
                .andExpect(status().isOk());
        verify(boardRepository).countQna(1, null);
    }
}
