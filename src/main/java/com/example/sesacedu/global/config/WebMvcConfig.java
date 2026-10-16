package com.example.sesacedu.global.config;

import com.example.sesacedu.global.auth.CurrentUserArgumentResolver;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * 강의 Controller의 CurrentUser 인자를 처리할 Resolver를 등록합니다.
 * CORS 정책은 기존 CorsConfig에서 관리합니다.
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final CurrentUserArgumentResolver currentUserArgumentResolver;

    public WebMvcConfig(
            CurrentUserArgumentResolver currentUserArgumentResolver
    ) {
        this.currentUserArgumentResolver = currentUserArgumentResolver;
    }

    @Override
    public void addArgumentResolvers(
            List<HandlerMethodArgumentResolver> resolvers
    ) {
        resolvers.add(currentUserArgumentResolver);
    }
}