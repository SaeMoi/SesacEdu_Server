package com.example.sesacedu.global.storage;

import org.springframework.web.multipart.MultipartFile;

/**
 * 파일 저장소 추상화.
 *
 * 나중에 S3 가 준비되면 이 인터페이스의 구현만 S3 용으로 갈아끼운다.
 * 신청 로직(서비스)은 이 인터페이스만 바라보므로 바꿀 필요가 없다.
 */
public interface FileStorage {

    /**
     * 파일을 저장하고, 저장 경로(키)를 반환한다.
     *
     * @param file   업로드된 파일
     * @param prefix 저장 위치 구분용 접두어 (예: "career")
     * @return 저장된 경로/키 (DB 의 career_file 에 넣을 값)
     */
    String store(MultipartFile file, String prefix);

    /**
     * 저장 경로(키)로 다운로드 URL 을 만든다.
     *
     * S3 가 붙으면 이 메서드만 presigned URL 생성으로 바꾸면 된다.
     * (컨트롤러·서비스는 이 인터페이스만 보므로 수정 불필요)
     *
     * @param path DB 에 저장된 경로/키 (work_path 등)
     * @return 브라우저가 바로 받을 수 있는 다운로드 URL
     */
    String getDownloadUrl(String path);
}