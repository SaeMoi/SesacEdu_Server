package com.example.sesacedu.student.lecture;

import com.example.sesacedu.global.exception.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * DB 에 적힌 파일 경로를 실제 파일로 바꿔 주는 자리.
 *
 * lectures.video 와 lectures.material 에는 "/files/videos/a.mp4" 같은 문자열이 들어 있다.
 * 이 값은 강사가 업로드할 때 서버가 적는 값이지만, 그렇다고 그대로 믿고 열면 안 된다.
 * DB 값이 한 번이라도 조작되면(업로드 쪽 검증이 비거나, SQL 주입이 성공하거나,
 * 운영자가 손으로 고치거나) 그 문자열이 그대로 파일 열기 인자가 되기 때문이다.
 *
 * 그래서 두 단계로 막는다.
 *   1. 저장소 뿌리(root) 아래의 상대 경로로만 해석한다. 맨 앞의 / 는 떼어 낸다.
 *      떼지 않으면 "/etc/passwd" 가 절대 경로로 해석돼 뿌리를 벗어난다.
 *   2. 합친 경로를 정규화한 뒤 다시 뿌리 아래인지 확인한다. ".." 가 몇 개 끼어 있든
 *      정규화 결과가 뿌리를 벗어나면 거절한다.
 *
 * 1번만 하면 "a/../../etc/passwd" 가 통과한다. 2번만 하면 절대 경로가 통과한다.
 * 둘 다 필요하다.
 */
@Component
public class LectureFileStorage {

    private final Path root;

    public LectureFileStorage(@Value("${app.storage.root:./storage}") String configuredRoot) {
        // toAbsolutePath + normalize 를 먼저 해 둔다. 비교 기준 자체가 상대 경로면
        // 아래의 startsWith 비교가 실행 디렉터리에 따라 달라진다.
        this.root = Paths.get(configuredRoot).toAbsolutePath().normalize();
    }

    /**
     * DB 에 적힌 경로를 실제 파일로 바꾼다.
     *
     * 못 찾거나 뿌리를 벗어나면 404 를 던진다. "경로를 벗어났다"와 "파일이 없다"를
     * 다른 응답으로 구분하지 않는다. 구분해 주면 어떤 경로가 존재하는지 알려 주는 셈이다.
     */
    public Path resolve(String storedPath, String notFoundCode, String notFoundMessage) {
        if (storedPath == null || storedPath.trim().isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, notFoundCode, notFoundMessage);
        }

        // 1단계: 절대 경로로 해석될 여지를 없앤다. 윈도우의 \ 도 / 로 맞춘다.
        String relative = storedPath.trim().replace('\\', '/');
        while (relative.startsWith("/")) {
            relative = relative.substring(1);
        }
        if (relative.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, notFoundCode, notFoundMessage);
        }

        Path candidate;
        try {
            candidate = root.resolve(relative).normalize();
        } catch (InvalidPathException ex) {
            // 윈도우에서 ':' 처럼 경로에 쓸 수 없는 글자가 들어 있으면 여기로 온다.
            throw new ApiException(HttpStatus.NOT_FOUND, notFoundCode, notFoundMessage);
        }

        // 2단계: 정규화 결과가 뿌리 아래인지 확인한다. 이 검사가 실제 차단선이다.
        if (!candidate.startsWith(root)) {
            throw new ApiException(HttpStatus.NOT_FOUND, notFoundCode, notFoundMessage);
        }

        // 디렉터리나 심볼릭 링크를 열어 주지 않는다. 링크는 뿌리 안에 있으면서
        // 바깥을 가리킬 수 있어 위의 경로 검사만으로는 막히지 않는다.
        if (!Files.isRegularFile(candidate) || Files.isSymbolicLink(candidate)) {
            throw new ApiException(HttpStatus.NOT_FOUND, notFoundCode, notFoundMessage);
        }

        return candidate;
    }

    /** 설정된 저장소 뿌리. 기동 로그와 테스트에서 쓴다. */
    public Path getRoot() {
        return root;
    }
}
