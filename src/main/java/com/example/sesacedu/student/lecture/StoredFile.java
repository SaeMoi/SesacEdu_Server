package com.example.sesacedu.student.lecture;

import java.nio.file.Path;

/**
 * 내려보낼 파일 한 건. 실제 경로와 다운로드할 때 쓸 이름을 들고 있다.
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
