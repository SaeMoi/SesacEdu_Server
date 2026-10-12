package com.example.sesacedu.global.storage;

import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * FileStorage 로컬 구현(임시).
 * 실제 디스크 저장 대신 경로 문자열만 만들어 돌려준다 — S3 전환 전까지 흐름 확인용.
 * 추후 S3Storage @Component 로 바꿔 끼우면 서비스 코드 수정 없이 교체된다.
 */
@Component
public class LocalFileStorage implements FileStorage {

    @Override
    public String store(MultipartFile file, String prefix) {
        String original = file.getOriginalFilename();
        String safeName = (original == null || original.isBlank()) ? "file" : original;
        // prefix/UUID_원본파일명 형태의 경로를 만든다.
        String key = UUID.randomUUID().toString().substring(0, 8);
        return prefix + "/" + key + "_" + safeName;
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