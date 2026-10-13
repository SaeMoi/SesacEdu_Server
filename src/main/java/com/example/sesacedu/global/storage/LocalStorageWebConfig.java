package com.example.sesacedu.global.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Paths;

/**
 * 로컬 저장 파일을 /files/** 로 내려주는 정적 리소스 핸들러 (테스트용).
 * app.file.type=local(기본) 일 때만 활성화. S3 로 바꾸면 비활성화된다.
 */
@Configuration
@ConditionalOnProperty(name = "app.file.type", havingValue = "local", matchIfMissing = true)
public class LocalStorageWebConfig implements WebMvcConfigurer {

    private final String baseDir;

    public LocalStorageWebConfig(@Value("${app.file.local.base-dir:./uploads}") String baseDir) {
        this.baseDir = baseDir;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // file:/.../uploads/  형태의 location
        String location = Paths.get(baseDir).toAbsolutePath().normalize().toUri().toString();
        registry.addResourceHandler("/files/**").addResourceLocations(location);
    }
}