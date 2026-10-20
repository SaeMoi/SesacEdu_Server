package com.example.sesacedu.student.lecture;

import com.example.sesacedu.global.storage.FileStorage;
import com.example.sesacedu.global.auth.AccessTokenVerifier;
import com.example.sesacedu.global.auth.CurrentUser;
import com.example.sesacedu.global.auth.CurrentUserArgumentResolver;
import com.example.sesacedu.global.exception.GlobalExceptionHandler;
import com.example.sesacedu.student.lecture.dto.ChapterResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 수강 화면 쓰기·전송 API 의 HTTP 계약. 사용자 10 은 강의 1 의 수강자다.
 */
class LectureClassroomWriteTest {

    static final String TOKEN = "Bearer test-token";
    static final byte[] VIDEO = "0123456789".getBytes(StandardCharsets.US_ASCII);

    @TempDir
    Path base;

    LectureRepository lectureRepository;
    LectureClassroomRepository classroomRepository;
    DefaultListableBeanFactory beans;
    Path root;

    @BeforeEach
    void setUp() throws Exception {
        lectureRepository = mock(LectureRepository.class);
        classroomRepository = mock(LectureClassroomRepository.class);
        beans = new DefaultListableBeanFactory();

        root = Files.createDirectories(base.resolve("storage"));
        Files.createDirectories(root.resolve("files/1"));
        Files.write(root.resolve("files/1/video.mp4"), VIDEO);
        Files.write(root.resolve("files/1/material.pdf"), "%PDF-1.4".getBytes(StandardCharsets.US_ASCII));
    }

    MockMvc mvcAs(Integer userId) {
        if (userId != null && !beans.containsSingleton("verifier")) {
            beans.registerSingleton("verifier", (AccessTokenVerifier) token -> new CurrentUser(userId));
        }
        return MockMvcBuilders
                .standaloneSetup(new LectureClassroomController(
                        new LectureClassroomService(lectureRepository, classroomRepository,
                                new LectureFileStorage(root.toString()), mock(FileStorage.class)),
                        new FileResponses()))
                .setCustomArgumentResolvers(
                        new CurrentUserArgumentResolver(beans.getBeanProvider(AccessTokenVerifier.class)))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    ClassroomRow lecture(String video, String videoName, Integer duration, String material, String materialName) {
        return new ClassroomRow(1, "웹 보안 입문", video, videoName, duration,
                material, materialName, "1차 과제", "정리해서 제출", "PDF, 10MB 이하");
    }

    ClassroomRow lectureOne() {
        return lecture("/files/1/video.mp4", "1강.mp4", 3600, "/files/1/material.pdf", "자료.pdf");
    }

    void enrolled(ClassroomRow row, EnrollmentProgress progress) {
        when(classroomRepository.findForEnrolled(1)).thenReturn(Optional.of(row));
        when(lectureRepository.findEnrollment(10, 1)).thenReturn(Optional.of(progress));
    }

    SubmissionRow submission(int aid, Integer score) {
        return new SubmissionRow(aid, "a.pdf", "메모", LocalDateTime.of(2026, 10, 14, 9, 0),
                score, score == null ? null : "좋습니다", score == null ? null : LocalDateTime.of(2026, 10, 14, 10, 0));
    }

    static final String SUBMIT = "{\"work\":\"/files/sub/a.pdf\",\"workFilename\":\"a.pdf\",\"note\":\"메모\"}";

    // ───────── 수강 화면 ─────────

    @Test
    void 비로그인이면_401이다() throws Exception {
        mvcAs(null).perform(get("/lectures/1/learn/video"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void 수강자가_아니어도_수강화면이_열린다() throws Exception {
        when(classroomRepository.findForEnrolled(1)).thenReturn(Optional.of(lectureOne()));
        when(lectureRepository.findEnrollment(12, 1)).thenReturn(Optional.empty());

        mvcAs(12).perform(get("/lectures/1/learn").header("Authorization", TOKEN))
                .andExpect(status().isOk());
    }

    @Test
    void 수강_화면은_목차를_함께_준다() throws Exception {
        enrolled(lectureOne(), new EnrollmentProgress(false, 510));
        when(classroomRepository.findChapters(1)).thenReturn(List.of(
                new ChapterResponse(0, "강의 소개와 학습 목표"),
                new ChapterResponse(510, "인증과 인가 이해하기")));

        mvcAs(10).perform(get("/lectures/1/learn").header("Authorization", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.videoUrl").value("/lectures/1/learn/video"))
                .andExpect(jsonPath("$.position").value(510))
                .andExpect(jsonPath("$.chapters[1].startSec").value(510))
                .andExpect(jsonPath("$.chapters[1].title").value("인증과 인가 이해하기"));
    }

    @Test
    void 목차가_없으면_빈_배열이다() throws Exception {
        enrolled(lectureOne(), new EnrollmentProgress(false, 0));
        when(classroomRepository.findChapters(1)).thenReturn(Collections.emptyList());

        mvcAs(10).perform(get("/lectures/1/learn").header("Authorization", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chapters").isEmpty());
    }

    // ───────── 파일 전송 ─────────

    @Test
    void 영상을_받는다() throws Exception {
        enrolled(lectureOne(), new EnrollmentProgress(false, 0));

        mvcAs(10).perform(get("/lectures/1/learn/video").header("Authorization", TOKEN))
                .andExpect(status().isOk())
                .andExpect(header().string("Accept-Ranges", "bytes"))
                .andExpect(header().string("Content-Disposition", "inline; filename=\"1강.mp4\""))
                .andExpect(content().bytes(VIDEO));
    }

    @Test
    void 구간을_요청하면_그_부분만_206으로_준다() throws Exception {
        enrolled(lectureOne(), new EnrollmentProgress(false, 0));

        MvcResult result = mvcAs(10).perform(get("/lectures/1/learn/video")
                        .header("Authorization", TOKEN)
                        .header("Range", "bytes=2-5"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string("Content-Range", "bytes 2-5/10"))
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).isEqualTo("2345");
    }

    @Test
    void 영상이_등록되지_않았으면_404다() throws Exception {
        enrolled(lecture(null, null, null, null, null), new EnrollmentProgress(false, null));

        mvcAs(10).perform(get("/lectures/1/learn/video").header("Authorization", TOKEN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("VIDEO_NOT_FOUND"));
    }

    @Test
    void 자료는_첨부파일로_받는다() throws Exception {
        enrolled(lectureOne(), new EnrollmentProgress(false, 0));

        mvcAs(10).perform(get("/lectures/1/material/download").header("Authorization", TOKEN))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"자료.pdf\""));
    }

    // ───────── 이어보기 · 수료 ─────────

    @Test
    void 위치를_안_보내면_400이다() throws Exception {
        enrolled(lectureOne(), new EnrollmentProgress(false, 100));

        mvcAs(10).perform(put("/lectures/1/learn/position").header("Authorization", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        verify(classroomRepository, never()).updatePosition(anyInt(), anyInt(), anyInt());
    }

    @Test
    void 보낸_위치를_저장한다() throws Exception {
        enrolled(lectureOne(), new EnrollmentProgress(false, 100));

        mvcAs(10).perform(put("/lectures/1/learn/position").header("Authorization", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"position\":160}"))
                .andExpect(status().isNoContent());
        verify(classroomRepository).updatePosition(1, 10, 160);
    }

    @Test
    void 영상_끝까지_보면_수료한다() throws Exception {
        enrolled(lectureOne(), new EnrollmentProgress(false, 3600));
        when(classroomRepository.markDone(1, 10)).thenReturn(1);

        mvcAs(10).perform(post("/lectures/1/complete").header("Authorization", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.done").value(true))
                .andExpect(jsonPath("$.requiredPosition").value(3600));
    }

    @Test
    void 하나도_안_봤어도_수료된다() throws Exception {
        enrolled(lectureOne(), new EnrollmentProgress(false, 0));
        when(classroomRepository.markDone(1, 10)).thenReturn(1);

        mvcAs(10).perform(post("/lectures/1/complete").header("Authorization", TOKEN))
                .andExpect(status().isOk());
        verify(classroomRepository).markDone(1, 10);
    }

    @Test
    void 이미_수료했으면_409다() throws Exception {
        enrolled(lectureOne(), new EnrollmentProgress(true, 3600));

        mvcAs(10).perform(post("/lectures/1/complete").header("Authorization", TOKEN))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_COMPLETED"));
    }

    // ───────── 과제 ─────────

    @Test
    void 과제를_제출한다() throws Exception {
        enrolled(lectureOne(), new EnrollmentProgress(false, 0));
        when(classroomRepository.findMySubmission(1, 10)).thenReturn(Optional.empty());
        when(classroomRepository.insertSubmission(1, 10, "/files/sub/a.pdf", "a.pdf", "메모")).thenReturn(101);

        mvcAs(10).perform(post("/lectures/1/assignments").header("Authorization", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content(SUBMIT))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(101));
    }

    @Test
    void 파일_없이_내용만_제출할_수_있다() throws Exception {
        enrolled(lectureOne(), new EnrollmentProgress(false, 0));
        when(classroomRepository.findMySubmission(1, 10)).thenReturn(Optional.empty());
        when(classroomRepository.insertSubmission(1, 10, null, null, "내용")).thenReturn(102);

        mvcAs(10).perform(post("/lectures/1/assignments").header("Authorization", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"내용\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(102));
    }

    @Test
    void 파일도_내용도_없으면_400이다() throws Exception {
        enrolled(lectureOne(), new EnrollmentProgress(false, 0));

        mvcAs(10).perform(post("/lectures/1/assignments").header("Authorization", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 내용_길이는_재지_않는다() throws Exception {
        enrolled(lectureOne(), new EnrollmentProgress(false, 0));
        String longNote = String.join("", Collections.nCopies(3001, "가"));

        mvcAs(10).perform(post("/lectures/1/assignments").header("Authorization", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"" + longNote + "\"}"))
                .andExpect(status().isCreated());
        verify(classroomRepository).insertSubmission(eq(1), eq(10), isNull(), isNull(), eq(longNote));
    }

    @Test
    void 이미_냈으면_다시_제출할_수_없다() throws Exception {
        enrolled(lectureOne(), new EnrollmentProgress(false, 0));
        when(classroomRepository.findMySubmission(1, 10)).thenReturn(Optional.of(submission(100, null)));

        mvcAs(10).perform(post("/lectures/1/assignments").header("Authorization", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content(SUBMIT))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_SUBMITTED"));
        verify(classroomRepository, never()).insertSubmission(anyInt(), anyInt(), any(), any(), any());
    }

    @Test
    void 제출물을_번호로_본다() throws Exception {
        enrolled(lectureOne(), new EnrollmentProgress(false, 0));
        when(classroomRepository.findSubmissionById(100)).thenReturn(Optional.of(submission(100, null)));

        mvcAs(10).perform(get("/lectures/1/assignments/100").header("Authorization", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignmentId").value(100))
                .andExpect(jsonPath("$.fileUrl").value("/lectures/1/assignments/100/file"));
    }

    @Test
    void 없는_제출물은_404다() throws Exception {
        enrolled(lectureOne(), new EnrollmentProgress(false, 0));
        when(classroomRepository.findSubmissionById(999)).thenReturn(Optional.empty());

        mvcAs(10).perform(get("/lectures/1/assignments/999").header("Authorization", TOKEN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SUBMISSION_NOT_FOUND"));
    }

    @Test
    void 채점_전이면_고칠_수_있다() throws Exception {
        enrolled(lectureOne(), new EnrollmentProgress(false, 0));
        when(classroomRepository.findSubmissionById(100)).thenReturn(Optional.of(submission(100, null)));

        mvcAs(10).perform(put("/lectures/1/assignments/100").header("Authorization", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"work\":\"/files/sub/b.pdf\",\"workFilename\":\"b.pdf\",\"note\":\"수정\"}"))
                .andExpect(status().isOk());
        verify(classroomRepository).updateSubmission(100, "/files/sub/b.pdf", "b.pdf", "수정");
    }

    @Test
    void 새_파일_없이_고치면_기존_첨부를_유지한다() throws Exception {
        enrolled(lectureOne(), new EnrollmentProgress(false, 0));
        when(classroomRepository.findSubmissionById(100)).thenReturn(Optional.of(submission(100, null)));
        when(classroomRepository.findWorkFile(100)).thenReturn(Optional.of(new String[]{"/files/sub/a.pdf", "a.pdf"}));

        mvcAs(10).perform(put("/lectures/1/assignments/100").header("Authorization", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"내용만 수정\"}"))
                .andExpect(status().isOk());
        verify(classroomRepository).updateSubmission(100, "/files/sub/a.pdf", "a.pdf", "내용만 수정");
    }

    @Test
    void 채점된_제출물도_고치고_지울_수_있다() throws Exception {
        enrolled(lectureOne(), new EnrollmentProgress(false, 0));
        when(classroomRepository.findSubmissionById(100)).thenReturn(Optional.of(submission(100, 90)));

        mvcAs(10).perform(put("/lectures/1/assignments/100").header("Authorization", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content(SUBMIT))
                .andExpect(status().isOk());
        mvcAs(10).perform(delete("/lectures/1/assignments/100").header("Authorization", TOKEN))
                .andExpect(status().isNoContent());
        verify(classroomRepository).deleteSubmission(100);
    }

    @Test
    void 채점_전이면_지울_수_있다() throws Exception {
        enrolled(lectureOne(), new EnrollmentProgress(false, 0));
        when(classroomRepository.findSubmissionById(100)).thenReturn(Optional.of(submission(100, null)));

        mvcAs(10).perform(delete("/lectures/1/assignments/100").header("Authorization", TOKEN))
                .andExpect(status().isNoContent());
        verify(classroomRepository).deleteSubmission(100);
    }

    @Test
    void 제출_파일을_받는다() throws Exception {
        Files.createDirectories(root.resolve("files/sub"));
        Files.write(root.resolve("files/sub/a.pdf"), "%PDF-sub".getBytes(StandardCharsets.US_ASCII));
        enrolled(lectureOne(), new EnrollmentProgress(false, 0));
        when(classroomRepository.findWorkFile(100)).thenReturn(Optional.of(new String[]{"/files/sub/a.pdf", "a.pdf"}));

        mvcAs(10).perform(get("/lectures/1/assignments/100/file").header("Authorization", TOKEN))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"a.pdf\""))
                .andExpect(content().bytes("%PDF-sub".getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    void 내용만_낸_제출물은_받을_파일이_없다() throws Exception {
        enrolled(lectureOne(), new EnrollmentProgress(false, 0));
        when(classroomRepository.findWorkFile(102)).thenReturn(Optional.of(new String[]{null, null}));

        mvcAs(10).perform(get("/lectures/1/assignments/102/file").header("Authorization", TOKEN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SUBMISSION_FILE_NOT_FOUND"));
    }

    // ───────── 자료 폴더의 다른 파일 · 링크 제출 ─────────

    @Test
    void 자료_폴더의_다른_파일을_이름으로_받는다() throws Exception {
        Files.write(root.resolve("files/1/appendix.pdf"), "%PDF-1.4".getBytes(StandardCharsets.US_ASCII));
        enrolled(lectureOne(), new EnrollmentProgress(false, 0));

        mvcAs(10).perform(get("/lectures/1/material/download").param("file", "appendix.pdf")
                        .header("Authorization", TOKEN))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"appendix.pdf\""));
    }

    @Test
    void 서버_자신을_가리키는_링크도_그대로_요청한다() throws Exception {
        enrolled(lectureOne(), new EnrollmentProgress(false, 0));

        // 주소를 가리지 않으므로 실제로 접속을 시도한다. 닫힌 포트라 받기에 실패한다.
        mvcAs(10).perform(post("/lectures/1/assignments").header("Authorization", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"work\":\"http://127.0.0.1:9/a.pdf\",\"note\":\"메모\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("LINK_FETCH_FAILED"));
    }
}
