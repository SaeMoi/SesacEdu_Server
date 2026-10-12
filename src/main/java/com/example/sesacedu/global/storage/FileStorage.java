package com.example.sesacedu.global.storage;

import org.springframework.web.multipart.MultipartFile;

/**
 * 파일 저장 추상화. 지금은 로컬(LocalFileStorage), 추후 S3 구현으로 교체 가능.
 * 서비스 코드는 이 인터페이스에만 의존한다.
 */
public interface FileStorage {

    /**
     * 파일을 저장하고 저장 경로(또는 키) 문자열을 반환한다.
     * @param file   업로드 파일
     * @param prefix 저장 위치 구분 (예: "lecture/video", "lecture/material", "lecture/picture")
     * @return 저장 경로 문자열 (DB 에 보관)
     */
    String store(MultipartFile file, String prefix);

    /**
     * 저장 경로로부터 다운로드/조회용 URL 을 만든다.
     * @param path store() 가 반환했던 경로
     * @return 접근 URL
     */
    String getDownloadUrl(String path);
}