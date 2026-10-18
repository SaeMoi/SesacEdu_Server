package com.example.sesacedu.student.lecture;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 파일 한 건을 HTTP 응답으로 만든다. 영상 · 자료 · 맛보기 · 과제 파일이 같이 쓴다.
 */
@Component
public class FileResponses {

    /** 영상은 inline(바로 재생), 자료는 attachment(다운로드)로 보낸다. */
    public ResponseEntity<Resource> send(StoredFile file, boolean asAttachment) {
        Path path = file.getPath();

        HttpHeaders headers = new HttpHeaders();
        // 영상 재생바를 끌 수 있게 구간 요청을 받는다.
        headers.set(HttpHeaders.ACCEPT_RANGES, "bytes");
        headers.setContentType(contentType(path));
        headers.set(HttpHeaders.CONTENT_DISPOSITION,
                (asAttachment ? "attachment" : "inline") + "; filename=\"" + file.getDownloadName() + "\"");

        return new ResponseEntity<>(new FileSystemResource(path), headers, HttpStatus.OK);
    }

    private MediaType contentType(Path path) {
        try {
            String type = Files.probeContentType(path);
            if (type != null) {
                return MediaType.parseMediaType(type);
            }
        } catch (IOException ignored) {
            // 알 수 없으면 아래 기본값을 쓴다.
        }
        return MediaType.APPLICATION_OCTET_STREAM;
    }
}
