package com.example.sesacedu.student.lecture;

import com.example.sesacedu.global.auth.AccessTokenVerifier;
import com.example.sesacedu.global.auth.CurrentUser;
import com.example.sesacedu.global.auth.CurrentUserArgumentResolver;
import com.example.sesacedu.global.exception.GlobalExceptionHandler;
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
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
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
 * 수강 화면 쓰기·전송 API 의 HTTP 계약과 권한 경계.
 *
 * 사용자 10 은 강의 1 의 수강자다. 사용자 번호는 언제나 토큰에서 나오고
 * 요청 본문이나 주소에서 받지 않는다. 그래서 저장소 호출 인자를 확인해
 * "누구의 것으로 저장·조회했는가"를 본다.
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
        // 뿌리 바깥 파일. DB 값이 조작돼도 여기에 닿으면 안 된다.
        Files.write(base.resolve("secret.txt"), "secret".getBytes(StandardCharsets.UTF_8));
    }

    /** 이어보기 검사에 쓰는 "지금". 테스트마다 같은 시각으로 고정한다. */
    static final Instant NOW = Instant.parse("2026-10-14T03:00:00Z");

    Timestamp secondsAgo(long seconds) {
        return Timestamp.from(NOW.minusSeconds(seconds));
    }

    MockMvc mvcAs(Integer userId) {
        // 한 테스트 안에서 여러 번 불려도 검증기는 한 번만 등록한다.
        if (userId != null && !beans.containsSingleton("verifier")) {
            beans.registerSingleton("verifier", (AccessTokenVerifier) token -> new CurrentUser(userId));
        }
        LectureClassroomService service = new LectureClassroomService(lectureRepository, classroomRepository,
                new LectureFileStorage(root.toString()));
        service.setClock(Clock.fixed(NOW, ZoneOffset.UTC));
        return MockMvcBuilders
                .standaloneSetup(new LectureClassroomController(service, new FileResponses()))
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

    // ───────── 영상 전송 ─────────

    @Test
    void 영상은_비로그인이면_401이다() throws Exception {
        mvcAs(null).perform(get("/lectures/1/learn/video"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void 영상은_수강자가_아니면_403이다() throws Exception {
        when(classroomRepository.findForEnrolled(1)).thenReturn(Optional.of(lectureOne()));
        when(lectureRepository.findEnrollment(12, 1)).thenReturn(Optional.empty());

        mvcAs(12).perform(get("/lectures/1/learn/video").header("Authorization", TOKEN))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_ENROLLED"));
    }

    @Test
    void 수강자는_영상을_그_자리에서_재생하는_형태로_받는다() throws Exception {
        enrolled(lectureOne(), new EnrollmentProgress(false, 0));

        mvcAs(10).perform(get("/lectures/1/learn/video").header("Authorization", TOKEN))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "video/mp4"))
                .andExpect(header().string("Accept-Ranges", "bytes"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                // 원본 파일명 "1강.mp4" 를 URL 인코딩해 filename* 에 넣는다.
                .andExpect(header().string("Content-Disposition", "inline; filename*=UTF-8''1%EA%B0%95.mp4"))
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
        // 본문은 4바이트인데 길이 헤더가 전체 10 으로 남아 있으면 브라우저가 나머지를 기다리며 멈춘다.
        assertThat(result.getResponse().getContentLengthLong()).isIn(-1L, 4L);
        assertThat(result.getResponse().getHeader("Content-Length")).isIn(null, "4");
    }

    @Test
    void 영상이_등록되지_않았으면_404다() throws Exception {
        enrolled(lecture(null, null, null, null, null), new EnrollmentProgress(false, null));

        mvcAs(10).perform(get("/lectures/1/learn/video").header("Authorization", TOKEN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("VIDEO_NOT_FOUND"));
    }

    @Test
    void DB_경로가_조작돼_뿌리를_벗어나도_파일에_닿지_않는다() throws Exception {
        enrolled(lecture("../secret.txt", "x.mp4", 10, "../../secret.txt", "y.pdf"),
                new EnrollmentProgress(false, 0));

        String video = mvcAs(10).perform(get("/lectures/1/learn/video").header("Authorization", TOKEN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("VIDEO_NOT_FOUND"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(video).doesNotContain("secret");
    }

    // ───────── 자료 전송 ─────────

    @Test
    void 자료는_원본파일명으로_저장되게_내려준다() throws Exception {
        enrolled(lectureOne(), new EnrollmentProgress(false, 0));

        mvcAs(10).perform(get("/lectures/1/material/download").header("Authorization", TOKEN))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename*=UTF-8''%EC%9E%90%EB%A3%8C.pdf"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
    }

    @Test
    void 파일명에_줄바꿈이_있어도_헤더가_쪼개지지_않는다() throws Exception {
        enrolled(lecture("/files/1/video.mp4", "a.mp4", 10,
                        "/files/1/material.pdf", "a\r\nSet-Cookie: hijack=1\r\n.pdf"),
                new EnrollmentProgress(false, 0));

        MvcResult result = mvcAs(10).perform(get("/lectures/1/material/download")
                        .header("Authorization", TOKEN))
                .andExpect(status().isOk())
                .andReturn();

        String disposition = result.getResponse().getHeader("Content-Disposition");
        assertThat(disposition).contains("%0D%0A").doesNotContain("\r").doesNotContain("\n");
        assertThat(result.getResponse().getHeader("Set-Cookie")).isNull();
    }

    @Test
    void 확장자를_모르는_파일은_octet_stream으로_보낸다() throws Exception {
        Files.write(root.resolve("files/1/page.html"), "<script>alert(1)</script>".getBytes(StandardCharsets.UTF_8));
        enrolled(lecture("/files/1/video.mp4", "a.mp4", 10, "/files/1/page.html", "page.html"),
                new EnrollmentProgress(false, 0));

        // 강사가 HTML 을 자료로 올려도 우리 도메인의 페이지로 렌더링되지 않아야 한다(저장형 XSS).
        mvcAs(10).perform(get("/lectures/1/material/download").header("Authorization", TOKEN))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/octet-stream"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
    }

    // ───────── 이어보기 위치 ─────────

    @Test
    void 위치_저장은_비로그인이면_401이다() throws Exception {
        mvcAs(null).perform(put("/lectures/1/learn/position")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"position\":10}"))
                .andExpect(status().isUnauthorized());
        verify(classroomRepository, never()).updatePosition(anyInt(), anyInt(), anyInt(), any());
    }

    @Test
    void 위치_저장은_수강자가_아니면_403이다() throws Exception {
        when(classroomRepository.findForEnrolled(1)).thenReturn(Optional.of(lectureOne()));
        when(lectureRepository.findEnrollment(12, 1)).thenReturn(Optional.empty());

        mvcAs(12).perform(put("/lectures/1/learn/position").header("Authorization", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"position\":10}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_ENROLLED"));
        verify(classroomRepository, never()).updatePosition(anyInt(), anyInt(), anyInt(), any());
    }

    @Test
    void 위치를_안_보내면_0으로_저장하지_않고_400이다() throws Exception {
        enrolled(lectureOne(), new EnrollmentProgress(false, 100));

        mvcAs(10).perform(put("/lectures/1/learn/position").header("Authorization", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        verify(classroomRepository, never()).updatePosition(anyInt(), anyInt(), anyInt(), any());
    }

    @Test
    void 음수_위치는_400이다() throws Exception {
        enrolled(lectureOne(), new EnrollmentProgress(false, 100));

        mvcAs(10).perform(put("/lectures/1/learn/position").header("Authorization", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"position\":-1}"))
                .andExpect(status().isBadRequest());
        verify(classroomRepository, never()).updatePosition(anyInt(), anyInt(), anyInt(), any());
    }

    void savePosition(int position) throws Exception {
        mvcAs(10).perform(put("/lectures/1/learn/position").header("Authorization", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"position\":" + position + "}"))
                .andExpect(status().isNoContent());
    }

    @Test
    void 처음_저장할_때는_여유_30초까지만_앞으로_간다() throws Exception {
        // 수강 직후 영상을 열지 않고 끝 위치를 보내는 경우다.
        enrolled(lectureOne(), new EnrollmentProgress(false, 100));
        when(classroomRepository.findPositionAt(1, 10)).thenReturn(Optional.empty());

        savePosition(999999);
        verify(classroomRepository).updatePosition(1, 10, 130, Timestamp.from(NOW));
    }

    @Test
    void 흐른_시간의_두_배_속도까지만_앞으로_간다() throws Exception {
        // 1000초 전에 100 을 저장했다 → 100 + 1000×2 + 30 = 2130 까지 받는다.
        enrolled(lectureOne(), new EnrollmentProgress(false, 100));
        when(classroomRepository.findPositionAt(1, 10)).thenReturn(Optional.of(secondsAgo(1000)));

        savePosition(999999);
        verify(classroomRepository).updatePosition(1, 10, 2130, Timestamp.from(NOW));
    }

    @Test
    void 정상_재생이면_보낸_위치를_그대로_저장한다() throws Exception {
        // 60초 전에 100 → 지금 160. 1배속 재생이다.
        enrolled(lectureOne(), new EnrollmentProgress(false, 100));
        when(classroomRepository.findPositionAt(1, 10)).thenReturn(Optional.of(secondsAgo(60)));

        savePosition(160);
        verify(classroomRepository).updatePosition(1, 10, 160, Timestamp.from(NOW));
    }

    @Test
    void 뒤로_가는_것은_언제든_받는다() throws Exception {
        enrolled(lectureOne(), new EnrollmentProgress(false, 3000));
        when(classroomRepository.findPositionAt(1, 10)).thenReturn(Optional.of(secondsAgo(1)));

        savePosition(10);
        verify(classroomRepository).updatePosition(1, 10, 10, Timestamp.from(NOW));
    }

    @Test
    void 영상_길이를_넘는_위치는_길이로_잘라_저장한다() throws Exception {
        // 3500 에서 1000초가 지났으니 시간상으로는 5530 까지 되지만 영상은 3600 초다.
        enrolled(lectureOne(), new EnrollmentProgress(false, 3500));
        when(classroomRepository.findPositionAt(1, 10)).thenReturn(Optional.of(secondsAgo(1000)));

        savePosition(999999);
        verify(classroomRepository).updatePosition(1, 10, 3600, Timestamp.from(NOW));
    }

    @Test
    void 위치는_토큰의_사용자_것으로_저장한다() throws Exception {
        enrolled(lectureOne(), new EnrollmentProgress(false, 100));
        when(classroomRepository.findPositionAt(1, 10)).thenReturn(Optional.of(secondsAgo(60)));

        mvcAs(10).perform(put("/lectures/1/learn/position").header("Authorization", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"position\":120,\"userId\":11}"))
                .andExpect(status().isNoContent());
        // 본문에 userId 를 끼워 보내도 무시된다.
        verify(classroomRepository).updatePosition(eq(1), eq(10), eq(120), any());
        verify(classroomRepository, never()).updatePosition(eq(1), eq(11), anyInt(), any());
    }

    @Test
    void 수강_화면은_목차를_시작_초_순서로_준다() throws Exception {
        enrolled(lectureOne(), new EnrollmentProgress(false, 510));
        when(classroomRepository.findChapters(1)).thenReturn(java.util.List.of(
                new com.example.sesacedu.student.lecture.dto.ChapterResponse(0, "강의 소개와 학습 목표"),
                new com.example.sesacedu.student.lecture.dto.ChapterResponse(510, "인증과 인가 이해하기")));

        mvcAs(10).perform(get("/lectures/1/learn").header("Authorization", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chapters[0].startSec").value(0))
                .andExpect(jsonPath("$.chapters[1].startSec").value(510))
                .andExpect(jsonPath("$.chapters[1].title").value("인증과 인가 이해하기"));
    }

    @Test
    void 목차가_없으면_빈_배열이다() throws Exception {
        enrolled(lectureOne(), new EnrollmentProgress(false, 0));

        mvcAs(10).perform(get("/lectures/1/learn").header("Authorization", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chapters").isArray())
                .andExpect(jsonPath("$.chapters").isEmpty());
    }

    @Test
    void 위치만_보내서는_수료할_수_없다() throws Exception {
        // 수강 직후 끝 위치를 보낸다 → 130 으로 저장된다 → 수료 조건(3600)에 못 미친다.
        enrolled(lectureOne(), new EnrollmentProgress(false, 100));
        when(classroomRepository.findPositionAt(1, 10)).thenReturn(Optional.empty());
        savePosition(3600);
        verify(classroomRepository).updatePosition(1, 10, 130, Timestamp.from(NOW));

        enrolled(lectureOne(), new EnrollmentProgress(false, 130));
        mvcAs(10).perform(post("/lectures/1/complete").header("Authorization", TOKEN))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOT_WATCHED_ENOUGH"));
    }

    // ───────── 수료 ─────────

    @Test
    void 수료는_비로그인이면_401이다() throws Exception {
        mvcAs(null).perform(post("/lectures/1/complete"))
                .andExpect(status().isUnauthorized());
        verify(classroomRepository, never()).markDone(anyInt(), anyInt());
    }

    @Test
    void 덜_봤으면_수료되지_않는다() throws Exception {
        // 영상 3600초를 끝까지 봐야 한다. 3599 는 1초 모자란다.
        enrolled(lectureOne(), new EnrollmentProgress(false, 3599));

        mvcAs(10).perform(post("/lectures/1/complete").header("Authorization", TOKEN))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOT_WATCHED_ENOUGH"));
        verify(classroomRepository, never()).markDone(anyInt(), anyInt());
    }

    @Test
    void 영상_끝까지_보면_수료로_처리한다() throws Exception {
        enrolled(lectureOne(), new EnrollmentProgress(false, 3600));
        when(classroomRepository.markDone(1, 10)).thenReturn(1);

        mvcAs(10).perform(post("/lectures/1/complete").header("Authorization", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lectureId").value(1))
                .andExpect(jsonPath("$.done").value(true))
                .andExpect(jsonPath("$.position").value(3600))
                .andExpect(jsonPath("$.duration").value(3600))
                .andExpect(jsonPath("$.requiredPosition").value(3600));
        verify(classroomRepository).markDone(1, 10);
    }

    @Test
    void 이미_수료했으면_다시_처리하지_않는다() throws Exception {
        enrolled(lectureOne(), new EnrollmentProgress(true, 3600));

        mvcAs(10).perform(post("/lectures/1/complete").header("Authorization", TOKEN))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_COMPLETED"));
        verify(classroomRepository, never()).markDone(anyInt(), anyInt());
    }

    @Test
    void 동시에_들어온_요청이_먼저_수료했으면_409다() throws Exception {
        enrolled(lectureOne(), new EnrollmentProgress(false, 3600));
        when(classroomRepository.markDone(1, 10)).thenReturn(0);

        mvcAs(10).perform(post("/lectures/1/complete").header("Authorization", TOKEN))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_COMPLETED"));
    }

    @Test
    void 영상_길이가_없으면_수료를_처리하지_않는다() throws Exception {
        // 조건 없이 통과시키면 이 API 하나로 수료가 된다.
        enrolled(lecture(null, null, null, null, null), new EnrollmentProgress(false, null));

        mvcAs(10).perform(post("/lectures/1/complete").header("Authorization", TOKEN))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DURATION_NOT_SET"));
        verify(classroomRepository, never()).markDone(anyInt(), anyInt());
    }

    // ───────── 과제 제출·조회·수정·삭제 ─────────

    static final String SUBMIT = "{\"work\":\"/files/sub/a.pdf\",\"workFilename\":\"a.pdf\",\"note\":\"메모\"}";

    SubmissionRow submission(int aid, Integer score) {
        return new SubmissionRow(aid, "a.pdf", "메모", LocalDateTime.of(2026, 10, 14, 9, 0),
                score, score == null ? null : "좋습니다", score == null ? null : LocalDateTime.of(2026, 10, 14, 10, 0));
    }

    @Test
    void 과제는_토큰의_사용자_이름으로_제출된다() throws Exception {
        enrolled(lectureOne(), new EnrollmentProgress(false, 0));
        when(classroomRepository.findMySubmission(1, 10)).thenReturn(Optional.empty());
        when(classroomRepository.insertSubmission(1, 10, "/files/sub/a.pdf", "a.pdf", "메모")).thenReturn(101);

        mvcAs(10).perform(post("/lectures/1/assignments").header("Authorization", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content(SUBMIT))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(101));
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
    void 파일도_내용도_없으면_400이다() throws Exception {
        enrolled(lectureOne(), new EnrollmentProgress(false, 0));

        mvcAs(10).perform(post("/lectures/1/assignments").header("Authorization", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        verify(classroomRepository, never()).insertSubmission(anyInt(), anyInt(), any(), any(), any());
    }

    @Test
    void 한글_3000자_내용은_받는다() throws Exception {
        enrolled(lectureOne(), new EnrollmentProgress(false, 0));
        when(classroomRepository.findMySubmission(1, 10)).thenReturn(Optional.empty());
        String note = String.join("", java.util.Collections.nCopies(3000, "가"));
        when(classroomRepository.insertSubmission(1, 10, null, null, note)).thenReturn(103);

        mvcAs(10).perform(post("/lectures/1/assignments").header("Authorization", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"" + note + "\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void 파일_없이_내용만_제출할_수_있다() throws Exception {
        enrolled(lectureOne(), new EnrollmentProgress(false, 0));
        when(classroomRepository.findMySubmission(1, 10)).thenReturn(Optional.empty());
        when(classroomRepository.insertSubmission(1, 10, null, null, "인증은 누구인지, 인가는 무엇을 할 수 있는지"))
                .thenReturn(102);

        // 파일명만 보내고 파일 경로가 없으면 파일명도 버린다. 남기면 없는 첨부파일이 화면에 뜬다.
        mvcAs(10).perform(post("/lectures/1/assignments").header("Authorization", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workFilename\":\"유령.pdf\",\"note\":\"인증은 누구인지, 인가는 무엇을 할 수 있는지\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(102));
        verify(classroomRepository).insertSubmission(1, 10, null, null, "인증은 누구인지, 인가는 무엇을 할 수 있는지");
    }

    @Test
    void 내용이_3000자를_넘으면_400이다() throws Exception {
        enrolled(lectureOne(), new EnrollmentProgress(false, 0));
        String longNote = String.join("", java.util.Collections.nCopies(3001, "가"));

        mvcAs(10).perform(post("/lectures/1/assignments").header("Authorization", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"" + longNote + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    // ───────── 내 과제 파일 다운로드 ─────────

    @Test
    void 내가_낸_파일을_원본이름으로_받는다() throws Exception {
        Files.createDirectories(root.resolve("files/sub"));
        Files.write(root.resolve("files/sub/a.pdf"), "%PDF-sub".getBytes(StandardCharsets.US_ASCII));
        enrolled(lectureOne(), new EnrollmentProgress(false, 0));
        when(classroomRepository.findMyWorkFile(1, 10, 100))
                .thenReturn(Optional.of(new String[]{"/files/sub/a.pdf", "내 과제.pdf"}));

        mvcAs(10).perform(get("/lectures/1/assignments/100/file").header("Authorization", TOKEN))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename*=UTF-8''%EB%82%B4%20%EA%B3%BC%EC%A0%9C.pdf"))
                .andExpect(content().bytes("%PDF-sub".getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    void 남의_제출_파일은_404다() throws Exception {
        enrolled(lectureOne(), new EnrollmentProgress(false, 0));
        when(classroomRepository.findMyWorkFile(1, 10, 555)).thenReturn(Optional.empty());

        mvcAs(10).perform(get("/lectures/1/assignments/555/file").header("Authorization", TOKEN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SUBMISSION_NOT_FOUND"));
    }

    @Test
    void 내용만_낸_제출물은_받을_파일이_없다() throws Exception {
        enrolled(lectureOne(), new EnrollmentProgress(false, 0));
        when(classroomRepository.findMyWorkFile(1, 10, 102)).thenReturn(Optional.of(new String[]{null, null}));

        mvcAs(10).perform(get("/lectures/1/assignments/102/file").header("Authorization", TOKEN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SUBMISSION_FILE_NOT_FOUND"));
    }

    @Test
    void 제출_파일_경로가_조작돼도_뿌리_밖으로_나가지_않는다() throws Exception {
        enrolled(lectureOne(), new EnrollmentProgress(false, 0));
        when(classroomRepository.findMyWorkFile(1, 10, 100))
                .thenReturn(Optional.of(new String[]{"../secret.txt", "x.pdf"}));

        mvcAs(10).perform(get("/lectures/1/assignments/100/file").header("Authorization", TOKEN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SUBMISSION_FILE_NOT_FOUND"));
    }

    @Test
    void 제출_파일은_수강자가_아니면_403이다() throws Exception {
        when(classroomRepository.findForEnrolled(1)).thenReturn(Optional.of(lectureOne()));
        when(lectureRepository.findEnrollment(12, 1)).thenReturn(Optional.empty());

        mvcAs(12).perform(get("/lectures/1/assignments/100/file").header("Authorization", TOKEN))
                .andExpect(status().isForbidden());
        verify(classroomRepository, never()).findMyWorkFile(anyInt(), anyInt(), anyInt());
    }

    @Test
    void 파일을_냈으면_제출물에_다운로드_주소가_붙고_아니면_없다() throws Exception {
        enrolled(lectureOne(), new EnrollmentProgress(false, 0));
        when(classroomRepository.findMySubmissionById(1, 10, 100)).thenReturn(Optional.of(submission(100, null)));
        when(classroomRepository.findMySubmissionById(1, 10, 102)).thenReturn(Optional.of(
                new SubmissionRow(102, null, "내용만", LocalDateTime.of(2026, 10, 14, 9, 0), null, null, null)));

        mvcAs(10).perform(get("/lectures/1/assignments/100").header("Authorization", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fileUrl").value("/lectures/1/assignments/100/file"));
        mvcAs(10).perform(get("/lectures/1/assignments/102").header("Authorization", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fileUrl").doesNotExist())
                .andExpect(jsonPath("$.note").value("내용만"));
    }

    @Test
    void 남의_제출물_번호를_넣으면_403이_아니라_404다() throws Exception {
        enrolled(lectureOne(), new EnrollmentProgress(false, 0));
        // 555 번은 다른 사람의 제출물이다. 조회는 (aid, Lid, 토큰 사용자) 로만 한다.
        when(classroomRepository.findMySubmissionById(1, 10, 555)).thenReturn(Optional.empty());

        mvcAs(10).perform(get("/lectures/1/assignments/555").header("Authorization", TOKEN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SUBMISSION_NOT_FOUND"));
        mvcAs(10).perform(put("/lectures/1/assignments/555").header("Authorization", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content(SUBMIT))
                .andExpect(status().isNotFound());
        mvcAs(10).perform(delete("/lectures/1/assignments/555").header("Authorization", TOKEN))
                .andExpect(status().isNotFound());

        verify(classroomRepository, never()).updateSubmission(anyInt(), anyInt(), anyInt(), any(), any(), any());
        verify(classroomRepository, never()).deleteSubmission(anyInt(), anyInt(), anyInt());
    }

    @Test
    void 채점된_제출물은_고칠_수도_지울_수도_없다() throws Exception {
        enrolled(lectureOne(), new EnrollmentProgress(false, 0));
        when(classroomRepository.findMySubmissionById(1, 10, 100)).thenReturn(Optional.of(submission(100, 90)));

        mvcAs(10).perform(put("/lectures/1/assignments/100").header("Authorization", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content(SUBMIT))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_SCORED"));
        mvcAs(10).perform(delete("/lectures/1/assignments/100").header("Authorization", TOKEN))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_SCORED"));

        verify(classroomRepository, never()).updateSubmission(anyInt(), anyInt(), anyInt(), any(), any(), any());
        verify(classroomRepository, never()).deleteSubmission(anyInt(), anyInt(), anyInt());
    }

    @Test
    void 채점_전이면_고칠_수_있고_점수는_건드리지_않는다() throws Exception {
        enrolled(lectureOne(), new EnrollmentProgress(false, 0));
        when(classroomRepository.findMySubmissionById(1, 10, 100)).thenReturn(Optional.of(submission(100, null)));
        when(classroomRepository.updateSubmission(1, 10, 100, "/files/sub/b.pdf", "b.pdf", "수정")).thenReturn(1);

        mvcAs(10).perform(put("/lectures/1/assignments/100").header("Authorization", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"work\":\"/files/sub/b.pdf\",\"workFilename\":\"b.pdf\",\"note\":\"수정\",\"score\":100}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignmentId").value(100));
        // 본문에 score 를 넣어도 저장 인자에 점수 자리가 없다.
        verify(classroomRepository).updateSubmission(1, 10, 100, "/files/sub/b.pdf", "b.pdf", "수정");
    }

    @Test
    void 채점_전이면_지울_수_있다() throws Exception {
        enrolled(lectureOne(), new EnrollmentProgress(false, 0));
        when(classroomRepository.findMySubmissionById(1, 10, 100)).thenReturn(Optional.of(submission(100, null)));
        when(classroomRepository.deleteSubmission(1, 10, 100)).thenReturn(1);

        mvcAs(10).perform(delete("/lectures/1/assignments/100").header("Authorization", TOKEN))
                .andExpect(status().isNoContent());
    }

    @Test
    void 조회한_뒤_수정하기_전에_채점되면_409다() throws Exception {
        enrolled(lectureOne(), new EnrollmentProgress(false, 0));
        // 처음 볼 때는 미채점, 수정 쿼리는 score IS NULL 조건에 걸려 0행, 다시 보니 채점됨.
        when(classroomRepository.findMySubmissionById(1, 10, 100))
                .thenReturn(Optional.of(submission(100, null)))
                .thenReturn(Optional.of(submission(100, 80)));
        when(classroomRepository.updateSubmission(anyInt(), anyInt(), anyInt(), any(), any(), any())).thenReturn(0);

        mvcAs(10).perform(put("/lectures/1/assignments/100").header("Authorization", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content(SUBMIT))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_SCORED"));
    }

    @Test
    void 조회한_뒤_지우기_전에_채점되면_409다() throws Exception {
        enrolled(lectureOne(), new EnrollmentProgress(false, 0));
        when(classroomRepository.findMySubmissionById(1, 10, 100))
                .thenReturn(Optional.of(submission(100, null)))
                .thenReturn(Optional.of(submission(100, 80)));
        when(classroomRepository.deleteSubmission(1, 10, 100)).thenReturn(0);

        mvcAs(10).perform(delete("/lectures/1/assignments/100").header("Authorization", TOKEN))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_SCORED"));
    }

    @Test
    void 지우는_사이에_다른_요청이_먼저_지웠으면_404다() throws Exception {
        enrolled(lectureOne(), new EnrollmentProgress(false, 0));
        when(classroomRepository.findMySubmissionById(1, 10, 100)).thenReturn(Optional.of(submission(100, null)));
        when(classroomRepository.deleteSubmission(1, 10, 100)).thenReturn(0);

        mvcAs(10).perform(delete("/lectures/1/assignments/100").header("Authorization", TOKEN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SUBMISSION_NOT_FOUND"));
    }
}
