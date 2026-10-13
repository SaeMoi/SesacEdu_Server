package com.example.sesacedu.student.lecture;

import java.nio.file.Path;

/**
 * 내려보낼 파일 한 건. 실제 경로와 사용자에게 보여 줄 이름을 함께 들고 다닌다.
 *
 * 실제 경로와 보여 줄 이름을 반드시 나눈다. 저장된 파일명은 겹침을 피하려고
 * 임의 문자열인 경우가 많고, 그걸 그대로 보여 주면 받는 쪽에서 무슨 파일인지 모른다.
 * 반대로 원본 파일명을 저장 경로로 쓰면 같은 이름이 서로를 덮어쓴다.
 */
public class StoredFile {

    private final Path path;
    private final String downloadName;

    public StoredFile(Path path, String downloadName) {
        this.path = path;
        this.downloadName = downloadName;
    }

    public Path getPath() {
        return path;
    }

    public String getDownloadName() {
        return downloadName;
    }
}
