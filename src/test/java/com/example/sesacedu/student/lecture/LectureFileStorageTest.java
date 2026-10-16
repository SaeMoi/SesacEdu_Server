package com.example.sesacedu.student.lecture;

import com.example.sesacedu.global.exception.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;

import java.nio.file.Files;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** DB 에 적힌 경로를 저장소 폴더 아래 실제 파일로 바꾼다. */
class LectureFileStorageTest {

    @TempDir
    Path base;

    Path root;
    LectureFileStorage storage;

    @BeforeEach
    void setUp() throws Exception {
        root = Files.createDirectories(base.resolve("storage"));
        Files.createDirectories(root.resolve("files/1"));
        Files.write(root.resolve("files/1/video.mp4"), new byte[]{1, 2, 3});
        storage = new LectureFileStorage(root.toString());
    }

    @Test
    void 저장소_폴더_아래에서_파일을_찾는다() throws Exception {
        assertThat(Files.isSameFile(storage.resolve("/files/1/video.mp4", "X", "없음"),
                root.resolve("files/1/video.mp4"))).isTrue();
        assertThat(Files.isSameFile(storage.resolve("files/1/video.mp4", "X", "없음"),
                root.resolve("files/1/video.mp4"))).isTrue();
    }

    @Test
    void 경로가_없거나_파일이_없으면_404다() {
        for (String p : new String[]{null, "", "files/1/none.mp4"}) {
            assertThatThrownBy(() -> storage.resolve(p, "X_NOT_FOUND", "없음"))
                    .isInstanceOf(ApiException.class)
                    .satisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        }
    }

    @Test
    void 상위_폴더_표기는_지우고_저장소_안에서만_찾는다() throws Exception {
        Files.write(base.resolve("outside.txt"), new byte[]{9});

        assertThatThrownBy(() -> storage.resolve("../outside.txt", "X_NOT_FOUND", "없음"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void 인코딩된_한글_파일명도_찾는다() throws Exception {
        Files.write(root.resolve("files/1/자료.pdf"), new byte[]{1});
        String encoded = "files/1/" + URLEncoder.encode("자료.pdf", StandardCharsets.UTF_8);

        assertThat(Files.isSameFile(storage.resolve(encoded, "X", "없음"), root.resolve("files/1/자료.pdf"))).isTrue();
    }

    @Test
    void 서버_자신을_가리키는_링크는_받지_않는다() {
        assertThatThrownBy(() -> storage.saveFromUrl(1, "http://localhost/a.pdf"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("BLOCKED_URL"));
    }
}
