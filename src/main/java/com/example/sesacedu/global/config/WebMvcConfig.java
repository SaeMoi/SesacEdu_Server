package com.example.sesacedu.global.config;

import com.example.sesacedu.global.auth.CurrentUserArgumentResolver;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * CurrentUserArgumentResolver 를 Spring MVC 에 등록한다.
 *
 * 해석기는 만들어 두기만 하면 동작하지 않는다. 여기에 등록해야 컨트롤러 인자에
 * CurrentUser 를 쓸 수 있다.
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final CurrentUserArgumentResolver currentUserArgumentResolver;

    public WebMvcConfig(CurrentUserArgumentResolver currentUserArgumentResolver) {
        this.currentUserArgumentResolver = currentUserArgumentResolver;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(currentUserArgumentResolver);
    }
}
