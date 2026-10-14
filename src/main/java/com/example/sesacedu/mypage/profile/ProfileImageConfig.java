package com.example.sesacedu.mypage.profile;

import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.servlet.MultipartConfigFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.util.unit.DataSize;
import org.springframework.web.multipart.MultipartResolver;
import org.springframework.web.multipart.support.StandardServletMultipartResolver;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.servlet.MultipartConfigElement;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.concurrent.TimeUnit;

/**
 * 프로필 사진 정적 경로 + 업로드 설정 (Notion API 명세서: 프로필 사진 변경 「이미지 보기」).
 *
 * - GET /images/profile/{파일명}: 인증 없이 열리는 정적 경로. 업로드 폴더 하나만 연결하고
 *   {user_id}_profile.png 형식 외 파일은 404. nosniff, inline, public max-age=86400.
 * - multipart 한도: 파일 10MB / 요청 11MB. 명세 한도(5MB)는 컨트롤러가 직접 검사해 413 FILE_TOO_LARGE를 준다.
 *   10MB를 넘는 요청은 MaxUploadSizeExceededException → edit_prof의 처리기에서 같은 413.
 *   resolveLazily: 업로드 본문을 컨트롤러 인자 처리 때 읽어야 컨트롤러의 @ExceptionHandler가 413을 잡을 수 있다.
 *   (이 두 빈이 있으면 application.yml의 spring.servlet.multipart.* 는 쓰이지 않는다)
 * - Tomcat swallowAbortedUploads: 10MB 초과로 업로드를 중단해도 남은 본문을 읽어 버린 뒤 응답한다.
 *   기본값(false)이면 Tomcat이 연결을 바로 끊어, 특히 Windows에서는 클라이언트가 413 JSON 대신
 *   "connection reset"(SocketException)을 받는다. 남은 본문은 최대 20MB까지만 읽고 그보다 크면 끊는다.
 */
@Configuration
public class ProfileImageConfig implements WebMvcConfigurer {

    private static final String PATTERN = ProfileImageStore.URL_PATH + "**";

    private final ProfileImageStore store;

    public ProfileImageConfig(ProfileImageStore store) {
        this.store = store;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String location = store.getDir().toUri().toString();
        if (!location.endsWith("/")) location += "/";      // 폴더가 아직 없으면 toUri()에 끝 / 가 없음
        registry.addResourceHandler(PATTERN)
                .addResourceLocations(location)
                .setCacheControl(CacheControl.maxAge(86400, TimeUnit.SECONDS).cachePublic());
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
                String uri = request.getRequestURI().substring(request.getContextPath().length());
                String name = uri.substring(ProfileImageStore.URL_PATH.length());
                if (!ProfileImageStore.FILE_NAME.matcher(name).matches()) {
                    response.sendError(404);
                    return false;
                }
                response.setHeader("X-Content-Type-Options", "nosniff");
                response.setHeader("Content-Disposition", "inline");
                return true;
            }
        }).addPathPatterns(PATTERN);
    }

    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> swallowAbortedUploads() {
        return factory -> {
            factory.addContextCustomizers(context -> context.setSwallowAbortedUploads(true));
            factory.addConnectorCustomizers(connector -> connector.setProperty("maxSwallowSize", String.valueOf(20 * 1024 * 1024)));
        };
    }

    @Bean
    public MultipartConfigElement multipartConfigElement() {
        MultipartConfigFactory f = new MultipartConfigFactory();
        f.setMaxFileSize(DataSize.ofMegabytes(10));
        f.setMaxRequestSize(DataSize.ofMegabytes(11));
        return f.createMultipartConfig();
    }

    @Bean(name = "multipartResolver")
    public MultipartResolver multipartResolver() {
        StandardServletMultipartResolver r = new StandardServletMultipartResolver();
        r.setResolveLazily(true);
        return r;
    }
}
