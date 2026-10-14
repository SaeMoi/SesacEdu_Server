package com.example.sesacedu.student.lecture;

import com.example.sesacedu.global.exception.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.File;
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

        File file = new File(root, storedPath);
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
