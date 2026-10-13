package com.example.sesacedu.global.storage;

import org.springframework.web.multipart.MultipartFile;

/**
 * 파일 저장 추상화. 구현을 갈아끼워 로컬/S3 를 전환한다.
 *   - 지금: LocalFileStorage (디스크, 테스트용)
 *   - 나중: S3FileStorage 추가 후 app.file.type=s3 로 전환
 */
public interface FileStorage {

    /** 파일 저장 → 저장 키(경로)를 반환. dir 예: "instructor/career". */
    String store(MultipartFile file, String dir);

    /** 저장 키로 다운로드 URL 발급. */
    String getDownloadUrl(String key);

    /** 저장 파일 삭제 (실패해도 조용히 무시). */
    void delete(String key);
}