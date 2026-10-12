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
        lectureService = new LectureService(lectureRepository);
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
    void listRejectsUnknownSort() {
        assertThatThrownBy(() -> lectureService.list(null, null, null, "price; DROP TABLE lectures", 1, 12))
                .isInstanceOf(ApiException.class)
                .extracting("code")
                .isEqualTo("INVALID_SORT");
    }

    @Test
    void listRejectsOutOfRangePage() {
        assertThatThrownBy(() -> lectureService.list(null, null, null, "latest", 0, 12))
                .isInstanceOf(ApiException.class)
                .extracting("code")
                .isEqualTo("INVALID_PAGE");
    }

    @Test
    void hugePageDoesNotOverflowOffset() {
        assertThatThrownBy(() -> lectureService.list(null, null, null, null, Integer.MAX_VALUE, 50))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("INVALID_PAGE");
        org.mockito.Mockito.verifyNoInteractions(lectureRepository);
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
}
