package com.example.sesacedu.global.storage;

import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
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

    /**
     * 저장 경로(키)로 다운로드 URL 을 만든다.
     *
     * 지금은 S3 가 없으므로 실제로 받을 수 있는 주소가 아니라,
     * S3 presigned URL 자리에 들어갈 "형태만 흉내 낸" 임시 URL 을 돌려준다.
     * (프론트 연동/화면 테스트용. 실제 다운로드는 S3 구현 후 가능하다.)
     *
     * TODO: S3 준비되면 이 메서드만 presigned URL 생성으로 교체한다.
     *       예) AmazonS3.generatePresignedUrl(bucket, key, 만료시간)
     */
    @Override
    public String getDownloadUrl(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        String encoded = URLEncoder.encode(path, StandardCharsets.UTF_8);
        // S3 가상 호스팅 형식을 흉내 낸 임시 주소 (실제로 받아지지는 않는다)
        return "https://sesacedu-local.example/files/" + encoded;
    }
}