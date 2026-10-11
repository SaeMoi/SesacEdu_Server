package com.example.sesacedu.global.storage;

import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

/**
 * S3 가 준비되기 전까지 쓰는 임시 FileStorage 구현.
 *
 * 실제로 파일을 어디 올리지는 않고, S3 에 올렸다면 생겼을 법한 "경로(키)"만
 * 만들어서 돌려준다. DB 흐름(career_file 에 경로 저장)을 지금 그대로 테스트할 수 있다.
 *
 * TODO: S3 준비되면 이 클래스를 지우고 S3FileStorage(구현체)를 만든다.
 *       (이 클래스에 붙은 @Component 를 떼거나 S3 구현에 @Primary 를 주면 교체된다.)
 */
@Component
public class LocalFileStorage implements FileStorage {

    @Override
    public String store(MultipartFile file, String prefix) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("빈 파일입니다.");
        }
        // S3 키처럼 "prefix/랜덤값_원본파일명" 형태의 경로를 만들어 돌려준다.
        String original = file.getOriginalFilename();
        String random = UUID.randomUUID().toString().substring(0, 8);
        return prefix + "/" + random + "_" + original;
    }
}