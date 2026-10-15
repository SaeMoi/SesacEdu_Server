package com.example.sesacedu.global.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Swagger(OpenAPI) 전역 설정.
 * 접속: http://localhost:8080/swagger-ui.html
 * (외장 Tomcat WAR 배포 시: http://localhost:8080/SesacEdu/swagger-ui.html)
 */

@Configuration
public class SwaggerConfig {

    @Bean
    public OpenAPI sesacEduOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("SesacEdu API")
                        .description("세싹에듀 백엔드 API 문서")
                        .version("v1"));
    }
}