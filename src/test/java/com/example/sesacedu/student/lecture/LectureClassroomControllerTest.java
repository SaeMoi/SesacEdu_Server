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
import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 수강 화면 3종의 HTTP 계약과 권한 경계 테스트.
 *
 * 토큰 문자열은 아무 값이나 쓴다. 이 테스트의 가짜 검증기가 항상 같은 사용자를
 * 돌려주기 때문이다. 실제 서명·만료 검증은 인증 담당자 구현체가 하고 여기서는
 * "검증을 통과한 뒤" 의 권한 분기만 본다.
 */
class LectureClassroomControllerTest {

    static final String TOKEN = "Bearer test-token";

    LectureRepository lectureRepository;
    LectureClassroomRepository classroomRepository;
    DefaultListableBeanFactory beans;

    @BeforeEach
    void setUp() {
        lectureRepository = mock(LectureRepository.class);
        classroomRepository = mock(LectureClassroomRepository.class);
        beans = new DefaultListableBeanFactory();
    }

    /** 검증기를 등록하지 않으면 비로그인 상태, 등록하면 그 사용자로 로그인한 상태가 된다. */
    MockMvc mvcAs(Integer userId) {
        if (userId != null) {
            beans.registerSingleton("verifier", (AccessTokenVerifier) token -> new CurrentUser(userId));
        }
        return MockMvcBuilders
                .standaloneSetup(new LectureClassroomController(
                        new LectureClassroomService(lectureRepository, classroomRepository,
                                new LectureFileStorage("./storage")),
                        new FileResponses()))
                .setCustomArgumentResolvers(
                        new CurrentUserArgumentResolver(beans.getBeanProvider(AccessTokenVerifier.class)))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    ClassroomRow lectureOne() {
        return new ClassroomRow(1, "웹 보안 입문",
                "/files/1/video.mp4", "1강.mp4", 3600,
                "/files/1/material.pdf", "자료.pdf",
                "1차 과제", "정리해서 제출", "PDF, 10MB 이하");
    }

    // ───────── 권한 경계 ─────────

    @Test
    void 비로그인이면_강의가_있는지도_알려주지_않는다() throws Exception {
        mvcAs(null).perform(get("/lectures/1/learn"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        // 강의 조회 자체를 하지 않으므로 존재 여부가 드러나지 않는다.
    }

    @Test
    void 로그인만_하면_수강중이_아니어도_수강화면이_열린다() throws Exception {
        when(classroomRepository.findForEnrolled(1)).thenReturn(Optional.of(lectureOne()));
        when(lectureRepository.findEnrollment(12, 1)).thenReturn(Optional.empty());

        mvcAs(12).perform(get("/lectures/1/learn").header("Authorization", TOKEN))
                .andExpect(status().isOk());
    }

    @Test
    void 공개되지_않은_강의는_404다() throws Exception {
        when(classroomRepository.findForEnrolled(3)).thenReturn(Optional.empty());

        mvcAs(10).perform(get("/lectures/3/learn").header("Authorization", TOKEN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LECTURE_NOT_FOUND"));
    }

    // ───────── 수강 ─────────

    @Test
    void 수강자는_재생주소를_받고_저장경로는_받지_않는다() throws Exception {
        when(classroomRepository.findForEnrolled(1)).thenReturn(Optional.of(lectureOne()));
        when(lectureRepository.findEnrollment(10, 1))
                .thenReturn(Optional.of(new EnrollmentProgress(false, 120)));

        mvcAs(10).perform(get("/lectures/1/learn").header("Authorization", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lectureId").value(1))
                .andExpect(jsonPath("$.videoUrl").value("/lectures/1/learn/video"))
                .andExpect(jsonPath("$.duration").value(3600))
                .andExpect(jsonPath("$.position").value(120))
                .andExpect(jsonPath("$.done").value(false))
                // 저장 경로가 어떤 필드로도 나가지 않아야 한다.
                .andExpect(jsonPath("$.video").doesNotExist())
                .andExpect(jsonPath("$.material").doesNotExist());
    }

    @Test
    void 영상이_없으면_재생주소를_주지_않는다() throws Exception {
        ClassroomRow noVideo = new ClassroomRow(2, "인증과 권한",
                null, null, null, null, null, null, null, null);
        when(classroomRepository.findForEnrolled(2)).thenReturn(Optional.of(noVideo));
        when(lectureRepository.findEnrollment(10, 2))
                .thenReturn(Optional.of(new EnrollmentProgress(true, null)));

        mvcAs(10).perform(get("/lectures/2/learn").header("Authorization", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.videoUrl").doesNotExist())
                .andExpect(jsonPath("$.position").doesNotExist())
                .andExpect(jsonPath("$.done").value(true));
    }

    // ───────── 자료 ─────────

    @Test
    void 자료는_원본파일명과_다운로드주소를_준다() throws Exception {
        when(classroomRepository.findForEnrolled(1)).thenReturn(Optional.of(lectureOne()));
        when(lectureRepository.findEnrollment(10, 1))
                .thenReturn(Optional.of(new EnrollmentProgress(false, null)));

        mvcAs(10).perform(get("/lectures/1/material").header("Authorization", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filename").value("자료.pdf"))
                .andExpect(jsonPath("$.downloadUrl").value("/lectures/1/material/download"));
    }

    @Test
    void 자료가_없으면_권한문제가_아니라_404다() throws Exception {
        ClassroomRow noMaterial = new ClassroomRow(2, "인증과 권한",
                null, null, null, null, null, null, null, null);
        when(classroomRepository.findForEnrolled(2)).thenReturn(Optional.of(noMaterial));
        when(lectureRepository.findEnrollment(10, 2))
                .thenReturn(Optional.of(new EnrollmentProgress(false, null)));

        mvcAs(10).perform(get("/lectures/2/material").header("Authorization", TOKEN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MATERIAL_NOT_FOUND"));
    }

    // ───────── 과제 ─────────

    @Test
    void 과제는_내_제출물만_함께_준다() throws Exception {
        when(classroomRepository.findForEnrolled(1)).thenReturn(Optional.of(lectureOne()));
        when(lectureRepository.findEnrollment(10, 1))
                .thenReturn(Optional.of(new EnrollmentProgress(false, null)));
        when(classroomRepository.findMySubmission(1, 10)).thenReturn(Optional.of(new SubmissionRow(
                100, "내_과제.pdf", "정리해서 냈습니다.",
                LocalDateTime.of(2026, 10, 12, 12, 0), 90,
                "핵심을 잘 짚었습니다.", LocalDateTime.of(2026, 10, 12, 13, 0))));

        mvcAs(10).perform(get("/lectures/1/assignment").header("Authorization", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("1차 과제"))
                .andExpect(jsonPath("$.fileType").value("PDF, 10MB 이하"))
                .andExpect(jsonPath("$.submission.assignmentId").value(100))
                .andExpect(jsonPath("$.submission.filename").value("내_과제.pdf"))
                .andExpect(jsonPath("$.submission.score").value(90));
    }

    @Test
    void 제출하지_않았으면_제출물이_생략된다() throws Exception {
        when(classroomRepository.findForEnrolled(1)).thenReturn(Optional.of(lectureOne()));
        when(lectureRepository.findEnrollment(11, 1))
                .thenReturn(Optional.of(new EnrollmentProgress(true, null)));
        when(classroomRepository.findMySubmission(1, 11)).thenReturn(Optional.empty());

        mvcAs(11).perform(get("/lectures/1/assignment").header("Authorization", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.submission").doesNotExist());
    }

    @Test
    void 과제가_등록되지_않았으면_404다() throws Exception {
        ClassroomRow noAssignment = new ClassroomRow(2, "인증과 권한",
                null, null, null, null, null, null, null, null);
        when(classroomRepository.findForEnrolled(2)).thenReturn(Optional.of(noAssignment));
        when(lectureRepository.findEnrollment(10, 2))
                .thenReturn(Optional.of(new EnrollmentProgress(false, null)));

        mvcAs(10).perform(get("/lectures/2/assignment").header("Authorization", TOKEN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ASSIGNMENT_NOT_FOUND"));
    }
}
