package com.example.sesacedu.global.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

/**
 * 로컬 디스크 저장 (개발/테스트용).
 * app.file.type=local(기본) 일 때만 활성화. S3 로 바꾸면 app.file.type=s3 → 비활성화.
 *
 * 저장 키 예: "instructor/career/9f1c...e2.pdf"
 * 다운로드 URL: base-url + "/" + key  (예: http://localhost:8080/files/instructor/career/9f1c...e2.pdf)
 *   → LocalStorageWebConfig 가 /files/** 를 base-dir 로 매핑해서 실제로 내려준다.
 */
@Component
@ConditionalOnProperty(name = "app.file.type", havingValue = "local", matchIfMissing = true)
public class LocalFileStorage implements FileStorage {

    private final Path baseDir;
    private final String baseUrl;

    public LocalFileStorage(
            @Value("${app.file.local.base-dir:./uploads}") String baseDir,
            @Value("${app.file.local.base-url:http://localhost:8080/files}") String baseUrl) {
        this.baseDir = Paths.get(baseDir).toAbsolutePath().normalize();
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        try {
            Files.createDirectories(this.baseDir);
        } catch (IOException e) {
            throw new UncheckedIOException("업로드 디렉터리 생성 실패: " + this.baseDir, e);
        }
    }

    @Override
    public String store(MultipartFile file, String dir) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("빈 파일입니다.");
        }
        String prefix = (dir == null || dir.isBlank()) ? "" : dir.replaceAll("^/+|/+$", "") + "/";
        String key = prefix + UUID.randomUUID() + ext(file.getOriginalFilename());

        Path target = baseDir.resolve(key).normalize();
        if (!target.startsWith(baseDir)) {               // 경로 탈출 방지
            throw new IllegalArgumentException("잘못된 경로입니다.");
        }
        try {
            Files.createDirectories(target.getParent());
            file.transferTo(target);
        } catch (IOException e) {
            throw new UncheckedIOException("파일 저장 실패", e);
        }
        return key;
    }

    @Override
    public String getDownloadUrl(String key) {
        if (key == null || key.isBlank()) return null;
        return baseUrl + "/" + key;
    }

    @Override
    public void delete(String key) {
        if (key == null || key.isBlank()) return;
        try {
            Path target = baseDir.resolve(key).normalize();
            if (target.startsWith(baseDir)) {
                Files.deleteIfExists(target);
            }
        } catch (IOException ignored) {
            // 삭제 실패는 무시
        }
    }

    private static String ext(String filename) {
        String e = StringUtils.getFilenameExtension(filename);
        return (e == null || e.isBlank()) ? "" : "." + e;
    }
}