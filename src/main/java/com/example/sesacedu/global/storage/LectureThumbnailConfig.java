package com.example.sesacedu.global.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

/**
 * GET /images/lecture/{파일명}: 강의 썸네일 정적 경로 (인증 없음).
 * 업로드 폴더({app.storage.root}/lecture/picture/)를 먼저 찾고, 없으면 classpath 의 기본 썸네일을 찾는다.
 */
@Configuration
public class LectureThumbnailConfig implements WebMvcConfigurer {

    private final String root;

    public LectureThumbnailConfig(@Value("${app.storage.root:./storage}") String root) {
        this.root = root;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String uploaded = Paths.get(root, LectureThumbnail.STORAGE_PREFIX)
                .toAbsolutePath().normalize().toUri().toString();
        if (!uploaded.endsWith("/")) uploaded += "/";      // 폴더가 아직 없으면 toUri()에 끝 / 가 없음
        registry.addResourceHandler(LectureThumbnail.URL_PATH + "**")
                .addResourceLocations(uploaded, "classpath:/static" + LectureThumbnail.URL_PATH)
                .setCacheControl(CacheControl.maxAge(3600, TimeUnit.SECONDS).cachePublic());
    }
}
