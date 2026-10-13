package com.example.sesacedu.student.lecture;

import com.example.sesacedu.global.exception.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DB 에 적힌 경로를 실제 파일로 바꾸는 경계의 테스트.
 *
 * 저장소 뿌리(root) 바로 바깥에 secret.txt 를 하나 둔다. 어떤 문자열을 넣어도
 * 그 파일에 닿으면 안 된다. 닿지 못했을 때의 응답은 "파일 없음"과 똑같은 404 여야 한다.
 */
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
        Files.write(base.resolve("secret.txt"), "secret".getBytes(StandardCharsets.UTF_8));
        storage = new LectureFileStorage(root.toString());
    }

    Path resolve(String stored) {
        return storage.resolve(stored, "X_NOT_FOUND", "없음");
    }

    void assertNotFound(String stored) {
        assertThatThrownBy(() -> resolve(stored))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    ApiException ex = (ApiException) e;
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(ex.getCode()).isEqualTo("X_NOT_FOUND");
                });
    }

    @Test
    void 앞의_슬래시는_떼고_뿌리_기준으로_찾는다() {
        assertThat(resolve("/files/1/video.mp4")).isEqualTo(root.resolve("files/1/video.mp4"));
        assertThat(resolve("files/1/video.mp4")).isEqualTo(root.resolve("files/1/video.mp4"));
        assertThat(resolve("//files/1/video.mp4")).isEqualTo(root.resolve("files/1/video.mp4"));
    }

    @Test
    void 뿌리_안에서_돌아오는_경로는_허용한다() {
        // 정규화 결과가 뿌리 안이면 된다. ".." 가 있다는 것만으로 막지 않는다.
        assertThat(resolve("files/x/../1/video.mp4")).isEqualTo(root.resolve("files/1/video.mp4"));
    }

    @Test
    void 상위_폴더로_빠져나가는_경로는_404다() {
        assertNotFound("../secret.txt");
        assertNotFound("/../secret.txt");
        assertNotFound("files/../../secret.txt");
        assertNotFound("files/1/../../../secret.txt");
    }

    @Test
    void 역슬래시로_써도_빠져나가지_못한다() {
        assertNotFound("..\\secret.txt");
        assertNotFound("files\\..\\..\\secret.txt");
    }

    @Test
    void 절대_경로를_넣어도_뿌리를_벗어나지_못한다() {
        // 윈도우에서는 C:\... 가 절대 경로로 해석돼 뿌리 밖이 되고,
        // 리눅스에서는 맨 앞 / 를 떼어 뿌리 아래의 없는 파일이 된다. 어느 쪽이든 404 다.
        assertNotFound(base.resolve("secret.txt").toAbsolutePath().toString());
    }

    @Test
    void 폴더나_없는_파일은_404다() {
        assertNotFound("files/1");
        assertNotFound("files/1/none.mp4");
    }

    @Test
    void 빈_값은_404다() {
        assertNotFound(null);
        assertNotFound("");
        assertNotFound("   ");
        assertNotFound("/");
    }

    @Test
    void 경로에_쓸_수_없는_글자가_있으면_404다() {
        // NUL 은 파일 이름에 쓸 수 없다. 잘라 읽히면 확장자 검사를 우회하는 데 쓰인다.
        assertNotFound("files/1/video.mp4\u0000.png");
    }

    @Test
    void 퍼센트_인코딩은_풀지_않는다() {
        // 이 계층은 URL 을 다루지 않는다. "%2e%2e" 를 ".." 로 풀어 주면 그게 우회로가 된다.
        assertNotFound("%2e%2e/secret.txt");
    }
}
