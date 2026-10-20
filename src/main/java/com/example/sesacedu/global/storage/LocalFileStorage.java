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
        // 폴더 부분은 버리고 파일명만 쓴다 (../ 로 저장 폴더를 벗어나지 않게).
        String baseName = original == null ? "" : original.replace('\\', '/');
        baseName = baseName.substring(baseName.lastIndexOf('/') + 1).trim();
        String safeName = baseName.isEmpty() || baseName.equals("..") ? "file" : baseName;
        // prefix/UUID_원본파일명 형태의 경로를 만든다.
        String key = UUID.randomUUID().toString().substring(0, 8);
        String relative = prefix + "/" + key + "_" + safeName;

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
    public String getDownloadUrl(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        String encoded = URLEncoder.encode(path, StandardCharsets.UTF_8);
        return "https://sesacedu-local.example/files/" + encoded;
    }
}
