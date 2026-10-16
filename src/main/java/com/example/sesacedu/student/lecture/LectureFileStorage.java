package com.example.sesacedu.student.lecture;

import com.example.sesacedu.global.exception.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.File;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * DB 에 적힌 파일 경로(예: /files/videos/a.mp4)를 저장소 폴더 아래 실제 파일로 바꾼다.
 * 저장소 폴더는 app.storage.root 이고 기본값은 ./storage 다.
 */
@Component
public class LectureFileStorage {


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

    /** 설정된 저장소 폴더. */
    public Path getRoot() {
        return Paths.get(root);
    }
}
