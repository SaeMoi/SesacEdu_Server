package com.example.sesacedu.global.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 저장소가 올라온 파일을 어디에 쓰는지 고정한다.
 * 보낸 폴더 구조를 살리는 동작과 zip 을 푸는 동작이 저장 위치를 어떻게 정하는지가 확인 대상이다.
 */
class LocalFileStorageTest {

    @TempDir
    Path base;

    /** 파일명에 폴더가 들어 있으면 그 구조대로 들어가고, UUID 는 파일명에만 붙는다. */
    @Test
    void 보낸_폴더_구조대로_저장된다() {
        LocalFileStorage storage = new LocalFileStorage(base.toString());

        String saved = storage.store(file("2주차/정리.pdf", "x"), "assignment/1");

        assertThat(saved).startsWith("assignment/1/2주차/").endsWith("_정리.pdf");
        assertThat(base.resolve(saved)).exists();
    }

    /** 파일명의 폴더 부분이 저장 폴더 위로 올라가면 그만큼 올라간 자리에 쓰인다. */
    @Test
    void 파일명의_상위_폴더_표기가_저장_폴더_밖을_가리킨다() {
        LocalFileStorage storage = new LocalFileStorage(base.resolve("storage").toString());

        // assignment/1 이 두 단계라 세 번 올라가야 저장소 루트 밖으로 나간다.
        String saved = storage.store(file("../../../밖.txt", "여기"), "assignment/1");
        Path written = base.resolve("storage").resolve(saved).normalize();

        assertThat(written.startsWith(base.resolve("storage"))).isFalse();
        assertThat(written).exists();
        assertThat(written).hasContent("여기");
    }

    /**
     * 역슬래시로 적은 상위 폴더 표기도 같은 자리를 가리킨다.
     * store() 가 역슬래시를 슬래시로 바꾼 뒤 경로를 만들기 때문에, 서비스에서 "../" 문자열만
     * 검사하면 이 형태는 그 검사를 지나친다.
     */
    @Test
    void 역슬래시로_적은_상위_폴더_표기도_통한다() {
        LocalFileStorage storage = new LocalFileStorage(base.resolve("storage").toString());

        String saved = storage.store(file("..\\..\\..\\밖2.txt", "역슬래시"), "assignment/1");
        Path written = base.resolve("storage").resolve(saved).normalize();

        assertThat(written.startsWith(base.resolve("storage"))).isFalse();
        assertThat(written).hasContent("역슬래시");
    }

    /** zip 안에 적힌 경로가 unpacked/ 위를 가리키면 그 자리에 풀린다. */
    @Test
    void zip_안의_상위_폴더_표기가_푸는_폴더_밖을_가리킨다() throws IOException {
        Path root = base.resolve("storage");
        LocalFileStorage storage = new LocalFileStorage(root.toString());
        String saved = storage.store(file("제출.zip", ""), "assignment/1");
        // unpacked/ 는 assignment/1 아래라 네 번 올라가야 저장소 루트 밖으로 나간다.
        Files.write(root.resolve(saved), zipWith("../../../../밖.jsp", "풀렸다"));

        storage.unpackZip(saved);

        Path escaped = base.resolve("밖.jsp");
        assertThat(escaped).exists();
        assertThat(escaped).hasContent("풀렸다");
    }

    private MockMultipartFile file(String name, String body) {
        return new MockMultipartFile("file", name, null, body.getBytes(StandardCharsets.UTF_8));
    }

    /** 경로를 직접 적은 항목 하나만 든 zip 을 만든다. ZipOutputStream 은 이름을 검사하지 않는다. */
    private byte[] zipWith(String entryName, String body) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry(entryName));
            zip.write(body.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return out.toByteArray();
    }
}
