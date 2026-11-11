package com.example.sesacedu.global.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * FileStorage 로컬 구현.
 * {app.storage.root}(기본 ./storage) 아래 prefix 폴더에 실제로 저장한다.
 * 학생 쪽 LectureFileStorage 도 같은 루트에서 파일을 찾으므로 영상·자료는 바로 재생/다운로드된다.
 * 추후 S3Storage @Component 로 바꿔 끼우면 서비스 코드 수정 없이 교체된다.
 */
@Component
public class LocalFileStorage implements FileStorage {

    private final Path root;

    public LocalFileStorage(@Value("${app.storage.root:./storage}") String root) {
        this.root = Paths.get(root).toAbsolutePath().normalize();
    }

    @Override
    public String store(MultipartFile file, String prefix) {
        String original = file.getOriginalFilename();
        // 업로더가 보낸 폴더 구조를 그대로 살린다. 자료를 폴더째 올렸을 때 받는 쪽에서도
        // 같은 구조로 보여야 하므로 파일명 앞의 폴더 부분을 버리지 않는다.
        String sent = original == null ? "" : original.replace('\\', '/').trim();
        int slash = sent.lastIndexOf('/');
        String dir = slash < 0 ? "" : sent.substring(0, slash + 1);
        String baseName = sent.substring(slash + 1);
        String safeName = baseName.isEmpty() ? "file" : baseName;
        // prefix/보낸폴더/UUID_원본파일명. UUID 는 같은 이름이 서로 덮어쓰지 않게 파일명에만 붙인다.
        String key = UUID.randomUUID().toString().substring(0, 8);
        String relative = prefix + "/" + dir + key + "_" + safeName;

        Path target = root.resolve(relative).normalize();
        try (InputStream in = file.getInputStream()) {
            Files.createDirectories(target.getParent());
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("파일 저장 실패: " + relative, e);
        }
        return relative;
    }

    @Override
    public void delete(String path) {
        if (path == null || path.isBlank()) return;
        // store() 가 만든 lecture/ 아래 파일만 지운다. 시드 데이터(/files/...)는 여러 강의가 같이 쓸 수 있어 남겨 둔다.
        if (!path.startsWith("lecture/")) return;
        Path target = root.resolve(path).normalize();
        if (!target.startsWith(root)) return;
        try {
            Files.deleteIfExists(target);
        } catch (IOException e) {
            throw new UncheckedIOException("파일 삭제 실패: " + path, e);
        }
    }

    @Override
    public void unpackZip(String path) {
        if (path == null || path.isBlank()) return;
        Path zip = root.resolve(path);
        Path into = zip.getParent().resolve("unpacked");
        try (ZipInputStream zin = new ZipInputStream(Files.newInputStream(zip))) {
            for (ZipEntry entry = zin.getNextEntry(); entry != null; entry = zin.getNextEntry()) {
                if (entry.isDirectory()) continue;
                // 압축 안에 적힌 경로를 그대로 쓴다. 폴더째 압축한 자료의 구조를 유지해야 한다.
                Path target = into.resolve(entry.getName());
                Files.createDirectories(target.getParent());
                Files.copy(zin, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            // 목록 미리보기만 못 하게 된다. 파일 자체는 이미 저장됐으므로 제출을 실패로 만들지 않는다.
        }
    }

    @Override
    public String getDownloadUrl(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        String encoded = URLEncoder.encode(path, StandardCharsets.UTF_8);
        return "https://sesacedu-local.example/files/" + encoded;
    }
}
