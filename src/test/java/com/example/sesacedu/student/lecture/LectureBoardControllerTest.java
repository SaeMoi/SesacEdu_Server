package com.example.sesacedu.student.lecture;

import com.example.sesacedu.global.auth.AccessTokenVerifier;
import com.example.sesacedu.global.auth.CurrentUser;
import com.example.sesacedu.global.auth.CurrentUserArgumentResolver;
import com.example.sesacedu.global.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 수강평·Q&A 의 HTTP 계약과 비밀글 처리 테스트.
 *
 * 강의 1 은 강사 7 의 공개 강의다. 질문 2 는 수강생 10 이 쓴 비밀글이다.
 * 이 비밀글을 누가 볼 수 있는지가 이 클래스의 중심이다.
 */
class LectureBoardControllerTest {

    static final String TOKEN = "Bearer test-token";
    static final LocalDateTime WRITTEN = LocalDateTime.of(2026, 10, 12, 15, 0);

    LectureRepository lectureRepository;
    LectureBoardRepository boardRepository;
    DefaultListableBeanFactory beans;

    @BeforeEach
    void setUp() {
        lectureRepository = mock(LectureRepository.class);
        boardRepository = mock(LectureBoardRepository.class);
        beans = new DefaultListableBeanFactory();

        // 강의 1 은 공개 상태이고 담당 강사는 7 이다.
        when(lectureRepository.findOpenById(1)).thenReturn(Optional.of(mock(LectureDetail.class)));
        when(boardRepository.findInstructorUserId(1)).thenReturn(Optional.of(7));
    }

    MockMvc mvcAs(Integer userId) {
        if (userId != null) {
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

    /** 질문 2 — 수강생 10 이 쓴 비밀글. */
    QnaRow secretQuestion() {
        return new QnaRow(2, 10, "수강생A", "결제 관련 비밀 질문",
                null, null, null, null, true, WRITTEN);
    }

    // ───────── 수강평 ─────────

    @Test
    void 수강평은_비로그인도_보고_평균평점을_함께_준다() throws Exception {
        when(lectureRepository.findReviewSummary(1)).thenReturn(new ReviewSummary(4.5, 2));
        when(boardRepository.countReviews(1)).thenReturn(2);
        when(boardRepository.findReviews(1, 0, 10)).thenReturn(List.of(
                new ReviewRow(10, "수강생A", 4, "설명이 친절합니다.", WRITTEN)));

        mvcAs(null).perform(get("/lectures/1/reviews"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rating").value(4.5))
                .andExpect(jsonPath("$.reviewCount").value(2))
                .andExpect(jsonPath("$.reviews[0].nickname").value("수강생A"))
                .andExpect(jsonPath("$.reviews[0].star").value(4))
                .andExpect(jsonPath("$.page.size").value(10));
    }

    @Test
    void 수강평이_없으면_평점을_생략한다() throws Exception {
        when(lectureRepository.findReviewSummary(1)).thenReturn(new ReviewSummary(null, 0));
        when(boardRepository.countReviews(1)).thenReturn(0);

        mvcAs(null).perform(get("/lectures/1/reviews"))
                .andExpect(status().isOk())
                // 0.0 으로 내려보내면 화면에서 "별 0개"로 읽힌다. 아예 빼는 쪽이 맞다.
                .andExpect(jsonPath("$.rating").doesNotExist())
                .andExpect(jsonPath("$.page.totalPages").value(0));
    }

    @Test
    void 공개되지_않은_강의의_글은_404다() throws Exception {
        when(lectureRepository.findOpenById(3)).thenReturn(Optional.empty());

        mvcAs(null).perform(get("/lectures/3/reviews"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LECTURE_NOT_FOUND"));
    }

    @Test
    void 페이지_범위를_벗어나면_400이다() throws Exception {
        mvcAs(null).perform(get("/lectures/1/reviews").param("size", "999"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PAGE"));
    }

    // ───────── Q&A 목록 ─────────

    @Test
    void 비로그인에게는_비밀글_제목이_가려진다() throws Exception {
        when(boardRepository.countQna(1, null)).thenReturn(1);
        when(boardRepository.findQna(1, 0, 10, null)).thenReturn(List.of(secretQuestion()));

        mvcAs(null).perform(get("/lectures/1/qna"))
                .andExpect(status().isOk())
                // 목록에서 빼지 않는다. 번호가 비면 그 자리에 글이 있다는 게 드러난다.
                .andExpect(jsonPath("$.questions[0].qid").value(2))
                .andExpect(jsonPath("$.questions[0].secret").value(true))
                .andExpect(jsonPath("$.questions[0].accessible").value(false))
                .andExpect(jsonPath("$.questions[0].title").value("비밀글입니다."));
    }

    @Test
    void 작성자_본인에게는_비밀글_제목이_보인다() throws Exception {
        when(boardRepository.countQna(1, null)).thenReturn(1);
        when(boardRepository.findQna(1, 0, 10, null)).thenReturn(List.of(secretQuestion()));

        mvcAs(10).perform(get("/lectures/1/qna").header("Authorization", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.questions[0].accessible").value(true))
                .andExpect(jsonPath("$.questions[0].title").value("결제 관련 비밀 질문"));
    }

    @Test
    void 담당_강사에게도_비밀글_제목이_보인다() throws Exception {
        when(boardRepository.countQna(1, null)).thenReturn(1);
        when(boardRepository.findQna(1, 0, 10, null)).thenReturn(List.of(secretQuestion()));

        mvcAs(7).perform(get("/lectures/1/qna").header("Authorization", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.questions[0].accessible").value(true));
    }

    @Test
    void 다른_강사에게는_보이지_않는다() throws Exception {
        when(boardRepository.countQna(1, null)).thenReturn(1);
        when(boardRepository.findQna(1, 0, 10, null)).thenReturn(List.of(secretQuestion()));

        // 이 강의의 담당 강사는 7 이다.
        mvcAs(99).perform(get("/lectures/1/qna").header("Authorization", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.questions[0].accessible").value(false))
                .andExpect(jsonPath("$.questions[0].title").value("비밀글입니다."));
    }

    @Test
    void 목록은_답변본문을_내보내지_않는다() throws Exception {
        QnaRow answered = new QnaRow(1, 10, "수강생A", "세션과 토큰의 차이",
                null, null, 7, WRITTEN, false, WRITTEN);
        when(boardRepository.countQna(1, null)).thenReturn(1);
        when(boardRepository.findQna(1, 0, 10, null)).thenReturn(List.of(answered));

        mvcAs(null).perform(get("/lectures/1/qna"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.questions[0].answered").value(true))
                .andExpect(jsonPath("$.questions[0].answer").doesNotExist())
                .andExpect(jsonPath("$.questions[0].question").doesNotExist());
    }

    // ───────── Q&A 상세 ─────────

    @Test
    void 비밀글_상세는_작성자가_열면_본문까지_나온다() throws Exception {
        QnaRow full = new QnaRow(2, 10, "수강생A", "결제 관련 비밀 질문",
                "비밀 본문입니다.", null, null, null, true, WRITTEN);
        when(boardRepository.findQnaDetail(1, 2)).thenReturn(Optional.of(full));

        mvcAs(10).perform(get("/lectures/1/qna/2").header("Authorization", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.question").value("비밀 본문입니다."))
                .andExpect(jsonPath("$.secret").value(true))
                .andExpect(jsonPath("$.answer").doesNotExist());
    }

    @Test
    void 비밀글_상세는_담당_강사도_연다() throws Exception {
        when(boardRepository.findQnaDetail(1, 2)).thenReturn(Optional.of(secretQuestion()));

        mvcAs(7).perform(get("/lectures/1/qna/2").header("Authorization", TOKEN))
                .andExpect(status().isOk());
    }

    @Test
    void 비밀글_상세는_다른_수강생이면_403이다() throws Exception {
        when(boardRepository.findQnaDetail(1, 2)).thenReturn(Optional.of(secretQuestion()));

        mvcAs(11).perform(get("/lectures/1/qna/2").header("Authorization", TOKEN))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SECRET_QNA"));
    }

    @Test
    void 비밀글_상세는_비로그인이면_401이다() throws Exception {
        when(boardRepository.findQnaDetail(1, 2)).thenReturn(Optional.of(secretQuestion()));

        mvcAs(null).perform(get("/lectures/1/qna/2"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 다른_강의의_질문번호로는_열리지_않는다() throws Exception {
        // 강의 1 에 질문 2 는 있지만, 강의 1 로 조회하면서 다른 강의의 질문 번호를 넣은 경우다.
        when(boardRepository.findQnaDetail(1, 777)).thenReturn(Optional.empty());

        mvcAs(10).perform(get("/lectures/1/qna/777").header("Authorization", TOKEN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("QNA_NOT_FOUND"));
    }
}
