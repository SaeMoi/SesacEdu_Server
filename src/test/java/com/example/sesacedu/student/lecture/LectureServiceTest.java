package com.example.sesacedu.student.lecture;

import com.example.sesacedu.global.auth.CurrentUser;
import com.example.sesacedu.global.exception.ApiException;
import com.example.sesacedu.student.lecture.EnrollmentProgress;
import com.example.sesacedu.student.lecture.LectureCard;
import com.example.sesacedu.student.lecture.LectureDetail;
import com.example.sesacedu.student.lecture.ReviewSummary;
import com.example.sesacedu.student.lecture.dto.LectureDetailResponse;
import com.example.sesacedu.student.lecture.dto.LectureListResponse;
import com.example.sesacedu.student.lecture.LectureRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LectureServiceTest {

    @Mock
    private LectureRepository lectureRepository;

    private LectureService lectureService;

    @BeforeEach
    void setUp() {
        lectureService = new LectureService(lectureRepository, new LectureFileStorage("./storage"));
    }

    @Test
    void listReturnsEmptyPageWhenNoOpenLectures() {
        when(lectureRepository.countOpenLectures(null, null, null)).thenReturn(0);

        LectureListResponse response = lectureService.list(null, null, null, null, null, null);

        assertThat(response.getLectures()).isEmpty();
        assertThat(response.getPage().getCurrent()).isEqualTo(1);
        assertThat(response.getPage().getSize()).isEqualTo(12);
        assertThat(response.getPage().getTotal()).isZero();
        assertThat(response.getPage().getTotalPages()).isZero();
    }

    @Test
    void listRejectsOutOfRangePage() {
        assertThatThrownBy(() -> lectureService.list(null, null, null, "latest", 0, 12))
                .isInstanceOf(ApiException.class)
                .extracting("code")
                .isEqualTo("INVALID_PAGE");
    }

    @Test
    void listAppliesLatestWhitelistAndPaging() {
        when(lectureRepository.countOpenLectures(null, null, null)).thenReturn(13);
        when(lectureRepository.findOpenLectures(
                eq(null), eq(null), eq(null),
                eq("l.created DESC NULLS LAST, l.Lid DESC"),
                eq(12), eq(12)))
                .thenReturn(List.of(new LectureCard(2, "인증", "한 줄", "/p.png", "강사", "웹 보안", "입문", 39000)));

        LectureListResponse response = lectureService.list(null, null, null, "latest", 2, 12);

        assertThat(response.getLectures()).hasSize(1);
        assertThat(response.getLectures().get(0).getLid()).isEqualTo(2);
        assertThat(response.getPage().getTotalPages()).isEqualTo(2);
        verify(lectureRepository).findOpenLectures(
                eq(null), eq(null), eq(null),
                eq("l.created DESC NULLS LAST, l.Lid DESC"),
                eq(12), eq(12));
    }

    @Test
    void detailGuestDoesNotIncludeLearnUrls() {
        stubOpenLecture();
        when(lectureRepository.findReviewSummary(12)).thenReturn(new ReviewSummary(4.6, 32));
        when(lectureRepository.findOtherOpenLectures(7, 12)).thenReturn(Collections.emptyList());

        LectureDetailResponse response = lectureService.detail(12, null);

        assertThat(response.getViewerState()).isEqualTo("guest");
        assertThat(response.getAction()).isEqualTo("purchase");
        assertThat(response.getPrice()).isEqualTo(49000);
        assertThat(response.getLearnUrl()).isNull();
        assertThat(response.getMaterialUrl()).isNull();
        assertThat(response.getInstructor().getInstructorId()).isEqualTo(7);
    }

    @Test
    void detailMemberWhenLoggedInButNotEnrolled() {
        stubOpenLecture();
        when(lectureRepository.findReviewSummary(12)).thenReturn(new ReviewSummary(null, 0));
        when(lectureRepository.findOtherOpenLectures(7, 12)).thenReturn(Collections.emptyList());
        when(lectureRepository.findEnrollment(3, 12)).thenReturn(Optional.empty());

        LectureDetailResponse response = lectureService.detail(12, new CurrentUser(3));

        assertThat(response.getViewerState()).isEqualTo("member");
        assertThat(response.getAction()).isEqualTo("purchase");
        assertThat(response.getLearnUrl()).isNull();
    }

    @Test
    void detailEnrolledHidesPriceAndAddsContinueUrls() {
        stubOpenLecture();
        when(lectureRepository.findReviewSummary(12)).thenReturn(new ReviewSummary(4.6, 32));
        when(lectureRepository.findOtherOpenLectures(7, 12)).thenReturn(Collections.emptyList());
        when(lectureRepository.findEnrollment(3, 12))
                .thenReturn(Optional.of(new EnrollmentProgress(false, 510)));

        LectureDetailResponse response = lectureService.detail(12, new CurrentUser(3));

        assertThat(response.getViewerState()).isEqualTo("enrolled");
        assertThat(response.getAction()).isEqualTo("continue");
        assertThat(response.getPrice()).isNull();
        assertThat(response.getDone()).isFalse();
        assertThat(response.getPosition()).isEqualTo(510);
        assertThat(response.getLearnUrl()).isEqualTo("/lectures/12/learn");
        assertThat(response.getMaterialUrl()).isEqualTo("/lectures/12/material");
        assertThat(response.getAssignmentUrl()).isEqualTo("/lectures/12/assignment");
    }

    @Test
    void detailMissingLectureIsNotFound() {
        when(lectureRepository.findOpenById(99)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> lectureService.detail(99, null))
                .isInstanceOf(ApiException.class)
                .extracting("code")
                .isEqualTo("LECTURE_NOT_FOUND");
    }

    private void stubOpenLecture() {
        when(lectureRepository.findOpenById(eq(12))).thenReturn(Optional.of(new LectureDetail(
                12, 7, "웹 보안 입문", "웹 보안의 첫걸음",
                "로그인과 권한 검사의 차이를 이해해요.\n웹 입력과 파일 업로드의 보안 원칙을 살펴봐요.",
                "웹 보안", "입문", "/media/lectures/12/thumb.png", "/media/lectures/12/preview.mp4",
                49000, "예시 강사", "실습으로 배우는 웹 보안", "정보보안 / 웹 보안", "에듀랩 보안 강사")));
    }

    // ───────── 프론트 /courses 화면과 맞춘 값 (2026-10-13 트러블슈팅) ─────────

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            "default, l.Lid DESC",
            "low,     'l.Lprice ASC, l.Lid DESC'",
            "high,    'l.Lprice DESC, l.Lid DESC'"
    })
    void 프론트_정렬값도_받는다(String sort, String expectedOrderBy) {
        when(lectureRepository.countOpenLectures(null, null, null)).thenReturn(1);
        when(lectureRepository.findOpenLectures(null, null, null, expectedOrderBy, 0, 12))
                .thenReturn(java.util.List.of());

        lectureService.list(null, null, null, sort, null, null);

        verify(lectureRepository).findOpenLectures(null, null, null, expectedOrderBy, 0, 12);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"all", "ALL"})
    void 난이도_all은_필터없음으로_바뀐다(String level) {
        when(lectureRepository.countOpenLectures(null, null, null)).thenReturn(0);

        lectureService.list(null, null, level, null, null, null);

        // "all" 을 값으로 넘기면 난이도가 all 인 강의를 찾아 0건이 된다.
        verify(lectureRepository).countOpenLectures(null, null, null);
    }

    @org.junit.jupiter.api.Test
    void 분류_all도_필터없음이다() {
        when(lectureRepository.countOpenLectures(null, null, null)).thenReturn(0);

        lectureService.list(null, "all", null, null, null, null);

        verify(lectureRepository).countOpenLectures(null, null, null);
    }

    @org.junit.jupiter.api.Test
    void 검색어_all은_그대로_검색한다() {
        when(lectureRepository.countOpenLectures("all", null, null)).thenReturn(0);

        lectureService.list("all", null, null, null, null, null);

        // 검색어는 사람이 입력한 값이다. all 이라는 단어를 찾는 것일 수 있다.
        verify(lectureRepository).countOpenLectures("all", null, null);
    }
}
