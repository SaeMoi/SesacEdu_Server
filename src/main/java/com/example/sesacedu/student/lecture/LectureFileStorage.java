package com.example.sesacedu.student.lecture;

import com.example.sesacedu.global.exception.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

/**
 * DB 에 적힌 파일 경로(예: /files/videos/a.mp4)를 저장소 폴더 아래 실제 파일로 바꾼다.
 * 저장소 폴더는 app.storage.root 이고 기본값은 ./storage 다.
 */
@Component
public class LectureFileStorage {

    // 링크로 받는 파일의 크기 한도. 큰 파일로 디스크가 차는 것을 막는다.
    private static final int MAX_LINK_BYTES = 5 * 1024 * 1024;
    private final String root;

    public LectureFileStorage(@Value("${app.storage.root:./storage}") String root) {
        this.root = root;
    }

    /** 경로에 해당하는 파일을 찾는다. 없으면 404 다. */
    public Path resolve(String storedPath, String notFoundCode, String notFoundMessage) {
        if (storedPath == null || storedPath.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, notFoundCode, notFoundMessage);
        }

        // 상위 폴더로 올라가는 표기를 지운다. 지운 뒤 다시 생기면 또 지운다.
        String cleaned = storedPath;
        String before;
        do {
            before = cleaned;
            cleaned = cleaned.replace("../", "").replace("..\\", "");
        } while (!cleaned.equals(before));

        // 한글 파일명은 인코딩된 채로 오는 경우가 있어 한 번 더 푼다.
        try {
            cleaned = URLDecoder.decode(cleaned, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PATH", "파일 경로가 올바르지 않습니다.");
        }

        File file = new File(root, cleaned);
        // 저장소 폴더 밖이면 거부한다.
        if (!file.getAbsolutePath().startsWith(new File(root).getAbsolutePath())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "INVALID_PATH", "파일 경로가 올바르지 않습니다.");
        }
        if (!file.exists()) {
            throw new ApiException(HttpStatus.NOT_FOUND, notFoundCode, notFoundMessage);
        }
        return file.toPath();
    }

    /**
     * 링크(http·https)로 낸 과제 파일을 받아 저장소에 보관하고, 보관한 경로를 돌려준다.
     * 파일 이름은 서버가 정한다(UUID). 원래 이름은 workFilename 에 남는다.
     */
    public String saveFromUrl(int lid, String link) {
        String stored = "/submissions/" + lid + "/" + UUID.randomUUID();
        try {
            URLConnection conn = new URL(link).openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            byte[] body;
            try (InputStream in = conn.getInputStream()) {
                body = in.readNBytes(MAX_LINK_BYTES);
            }
            Path target = Paths.get(root, stored);
            Files.createDirectories(target.getParent());
            Files.write(target, body);
        } catch (IOException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "LINK_FETCH_FAILED", "링크에서 파일을 받지 못했습니다.");
        }
        return stored;
    }

    /** 설정된 저장소 폴더. */
    public Path getRoot() {
        return Paths.get(root);
    }
}
