package com.example.sesacedu.global.config;

import com.example.sesacedu.global.auth.CurrentUserArgumentResolver;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * CurrentUserArgumentResolver 등록과 CORS 허용 출처를 함께 둔다.
 *
 * 해석기는 만들어 두기만 하면 동작하지 않는다. 여기에 등록해야 컨트롤러 인자에
 * CurrentUser 를 쓸 수 있다.
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final CurrentUserArgumentResolver currentUserArgumentResolver;
    private final String[] allowedOrigins;

    public WebMvcConfig(CurrentUserArgumentResolver currentUserArgumentResolver,
                        @Value("${app.cors.allowed-origins:http://localhost:5173,http://127.0.0.1:5173}")
                        String[] allowedOrigins) {
        this.currentUserArgumentResolver = currentUserArgumentResolver;
        this.allowedOrigins = allowedOrigins;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(currentUserArgumentResolver);
    }

    /**
     * 프론트 개발 서버(Vite 5173)에서 바로 호출할 수 있게 연다.
     *
     * 출처를 설정값으로 둔 이유는, 배포 주소가 정해지면 코드를 고치지 않고
     * 환경값만 바꿔 쓰기 위해서다. 기본값에 개발 서버 주소만 넣어 뒀다.
     *
     * allowedOrigins 에 "*" 를 쓰지 않는다. 우리는 Authorization 헤더로 인증하므로
     * 아무 출처나 열면 사용자가 접속해 있는 임의의 사이트가 그 사용자 토큰으로
     * 우리 API 를 호출하는 스크립트를 돌릴 수 있다.
     *
     * allowCredentials 는 켜지 않는다. 쿠키를 쓰지 않고 헤더로만 인증하기 때문이다.
     * 필요 없는데 켜 두면 "*" 를 쓸 수 없게 되는 제약만 생기고 이득이 없다.
     *
     * Content-Disposition 을 노출 헤더에 넣는다. 넣지 않으면 자료 다운로드 때
     * 프론트에서 원본 파일명을 읽을 수 없다. 브라우저는 기본적으로 몇 개의
     * 헤더만 스크립트에 보여 준다.
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOrigins(allowedOrigins)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                // Range 를 허용하고 Content-Range · Accept-Ranges 를 노출한다. 영상은 Authorization
                // 헤더가 필요해 <video src> 로 바로 걸 수 없고, fetch 로 구간을 받아 재생해야 한다.
                .allowedHeaders("Authorization", "Content-Type", "Range")
                .exposedHeaders("Content-Disposition", "Content-Range", "Accept-Ranges")
                .allowCredentials(false)
                .maxAge(3600);
    }
}
