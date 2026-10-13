package com.example.sesacedu.student.lecture;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.file.Path;

/**
 * 파일 한 건을 HTTP 응답으로 만드는 일을 한 곳에 모아 둔다.
 *
 * 영상·자료·맛보기 세 군데가 같은 헤더를 써야 한다. 컨트롤러마다 따로 쓰면
 * 한 곳에만 nosniff 가 빠지는 식으로 갈라지고, 그 한 곳이 구멍이 된다.
 */
@Component
public class FileResponses {

    /**
     * 중요한 헤더는 세 개다.
     *
     * Accept-Ranges: 이게 없으면 브라우저가 영상 재생바를 끌 수 없다. 처음부터
     * 끝까지 받아야만 재생되므로 긴 영상은 사실상 못 본다.
     *
     * Content-Disposition: 파일명을 그대로 넣으면 안 된다. 원본 파일명은 사용자가
     * 올린 문자열이라 줄바꿈이나 따옴표가 들어 있을 수 있고, 그러면 헤더가 쪼개져
     * 다른 헤더를 끼워 넣을 수 있다(헤더 주입). 그래서 filename* 로 URL 인코딩해
     * 넣고, 인코딩이 실패하면 파일명을 아예 붙이지 않는다.
     *
     * Content-Type: 추측에 맡기지 않고 확장자로 정한다. 알 수 없으면
     * application/octet-stream 이다. text/html 로 추측되는 일이 생기면 올린 파일이
     * 그 사이트의 페이지처럼 실행된다(저장형 XSS).
     */
    public ResponseEntity<Resource> send(StoredFile file, boolean asAttachment) {
        Path path = file.getPath();

        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ACCEPT_RANGES, "bytes");
        // Content-Length 는 직접 넣지 않는다. 구간 요청(206)이면 길이가 구간 크기로 바뀌어야 하는데,
        // 전체 길이를 박아 두면 여러 구간 응답에서 그 값이 남아 클라이언트가 나머지를 기다린다.
        // 전체 응답과 단일 구간은 Spring 의 변환기가 알맞은 값을 넣는다.
        headers.setContentType(contentType(path));

        // 추측 실행을 막는다. 브라우저가 Content-Type 을 무시하고 내용으로 판단하면
        // 위에서 정한 타입이 의미가 없어진다.
        headers.set("X-Content-Type-Options", "nosniff");

        String disposition = asAttachment ? "attachment" : "inline";
        String encoded = encodeFilename(file.getDownloadName());
        if (encoded != null) {
            headers.set(HttpHeaders.CONTENT_DISPOSITION,
                    disposition + "; filename*=UTF-8''" + encoded);
        } else {
            headers.set(HttpHeaders.CONTENT_DISPOSITION, disposition);
        }

        return new ResponseEntity<>(new FileSystemResource(path), headers, HttpStatus.OK);
    }

    /** 확장자로 Content-Type 을 정한다. 모르는 확장자는 octet-stream 이다. */
    private MediaType contentType(Path path) {
        String name = path.getFileName().toString().toLowerCase();
        if (name.endsWith(".mp4")) return MediaType.parseMediaType("video/mp4");
        if (name.endsWith(".webm")) return MediaType.parseMediaType("video/webm");
        if (name.endsWith(".pdf")) return MediaType.APPLICATION_PDF;
        if (name.endsWith(".zip")) return MediaType.parseMediaType("application/zip");
        if (name.endsWith(".png")) return MediaType.IMAGE_PNG;
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return MediaType.IMAGE_JPEG;
        return MediaType.APPLICATION_OCTET_STREAM;
    }

    /**
     * 파일명을 헤더에 넣을 수 있는 형태로 바꾼다.
     *
     * URLEncoder 는 공백을 + 로 바꾸는데 헤더에서는 %20 이어야 한다. 그래서 되돌린다.
     * 줄바꿈·따옴표는 인코딩 과정에서 %0A·%22 가 되므로 헤더가 쪼개지지 않는다.
     */
    private String encodeFilename(String name) {
        if (name == null || name.trim().isEmpty()) {
            return null;
        }
        try {
            return URLEncoder.encode(name, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException ex) {
            return null;
        }
    }
}
