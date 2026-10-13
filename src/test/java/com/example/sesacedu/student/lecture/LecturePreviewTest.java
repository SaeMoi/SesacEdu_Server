package com.example.sesacedu.student.lecture;

import com.example.sesacedu.global.auth.AccessTokenVerifier;
import com.example.sesacedu.global.auth.CurrentUserArgumentResolver;
import com.example.sesacedu.global.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 맛보기 영상. 공개 API 지만 공개(open) 강의에 한해서다.
 *
 * 상세 응답의 preview 는 저장 경로가 아니라 이 API 의 주소여야 한다.
 * 저장 경로를 그대로 내보내면 그 경로를 서빙하는 곳이 없어 재생이 안 되고,
 * 서빙하도록 열어 두면 같은 폴더의 본 강의 영상까지 주소만으로 받아 갈 수 있다.
 */
class LecturePreviewTest {

    static final byte[] CLIP = "preview-bytes".getBytes(StandardCharsets.US_ASCII);

    @TempDir
    Path base;

    LectureRepository repository;
    MockMvc mvc;

    @BeforeEach
    void setUp() throws Exception {
        Path root = Files.createDirectories(base.resolve("storage"));
        Files.createDirectories(root.resolve("files/12"));
        Files.write(root.resolve("files/12/preview.mp4"), CLIP);

        repository = mock(LectureRepository.class);
        when(repository.findReviewSummary(12)).thenReturn(new ReviewSummary(null, 0));
        when(repository.findOtherOpenLectures(7, 12)).thenReturn(Collections.emptyList());

        mvc = MockMvcBuilders
                .standaloneSetup(new LectureController(
                        new LectureService(repository, new LectureFileStorage(root.toString())),
                        new FileResponses()))
                .setCustomArgumentResolvers(new CurrentUserArgumentResolver(
                        new DefaultListableBeanFactory().getBeanProvider(AccessTokenVerifier.class)))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    LectureDetail lecture(String preview) {
        return new LectureDetail(12, 7, "웹 보안 입문", "한 줄", "본문", "웹 보안", "입문",
                "/files/12/thumb.png", preview, 30000, "강사", "소개", "웹", "경력");
    }

    @Test
    void 공개_강의의_맛보기는_비로그인도_받는다() throws Exception {
        when(repository.findOpenById(12)).thenReturn(Optional.of(lecture("/files/12/preview.mp4")));

        mvc.perform(get("/lectures/12/preview"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "video/mp4"))
                .andExpect(header().string("Accept-Ranges", "bytes"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(content().bytes(CLIP));
    }

    @Test
    void 비공개이거나_없는_강의의_맛보기는_404다() throws Exception {
        when(repository.findOpenById(99)).thenReturn(Optional.empty());

        mvc.perform(get("/lectures/99/preview"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LECTURE_NOT_FOUND"));
    }

    @Test
    void 맛보기가_등록되지_않았으면_404다() throws Exception {
        when(repository.findOpenById(12)).thenReturn(Optional.of(lecture(null)));

        mvc.perform(get("/lectures/12/preview"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PREVIEW_NOT_FOUND"));
    }

    @Test
    void 맛보기_경로가_조작돼도_뿌리_밖으로_나가지_않는다() throws Exception {
        Files.write(base.resolve("secret.txt"), "secret".getBytes(StandardCharsets.UTF_8));
        when(repository.findOpenById(12)).thenReturn(Optional.of(lecture("../secret.txt")));

        mvc.perform(get("/lectures/12/preview"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PREVIEW_NOT_FOUND"));
    }

    @Test
    void 강의_번호가_0이면_400이다() throws Exception {
        mvc.perform(get("/lectures/0/preview"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    @Test
    void 상세의_preview는_저장경로가_아니라_재생_API_주소다() throws Exception {
        when(repository.findOpenById(12)).thenReturn(Optional.of(lecture("/files/12/preview.mp4")));

        mvc.perform(get("/lectures/12"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.preview").value("/lectures/12/preview"));
    }

    @Test
    void 맛보기가_없으면_상세에_preview가_없다() throws Exception {
        when(repository.findOpenById(12)).thenReturn(Optional.of(lecture(null)));

        mvc.perform(get("/lectures/12"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.preview").doesNotExist());
    }
}
