package com.example.sesacedu.global;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Arrays;

/**
 * 프런트(다른 포트/도메인)에서 브라우저로 API를 부를 수 있게 허용할 출처 목록.
 *
 * - 값: app.cors.allowed-origins ← 환경변수 CORS_ALLOWED_ORIGINS (쉼표 구분)
 *   예) http://localhost:3000,http://localhost:5173
 * - "localhost:3000" 처럼 스킴 없이 적으면 http:// 를 붙인다 (브라우저 Origin 은 항상 스킴 포함).
 * - 비어 있으면 아무 출처도 허용하지 않는다 (같은 출처 호출과 Postman/curl 은 영향 없음).
 * - JWT 는 Authorization 헤더로 오가므로 쿠키 자격증명(allowCredentials)은 켜지 않는다.
 *   응답의 Authorization(로그인·재발급 토큰)은 exposedHeaders 로 프런트 JS 가 읽을 수 있게 연다.
 **/
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    private final String[] allowedOrigins;

    public CorsConfig(@Value("${app.cors.allowed-origins:}") String origins) {
        this.allowedOrigins = Arrays.stream(origins.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> s.contains("://") ? s : "http://" + s)
                .map(s -> s.endsWith("/") ? s.substring(0, s.length() - 1) : s)
                .toArray(String[]::new);
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        if (allowedOrigins.length == 0) {
            return;
        }
        registry.addMapping("/**")
                .allowedOrigins(allowedOrigins)
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("Authorization", "Content-Type", JwtUtil.REAUTH_HEADER)
                .exposedHeaders("Authorization")
                .allowCredentials(false)
                .maxAge(3600);
    }
}
