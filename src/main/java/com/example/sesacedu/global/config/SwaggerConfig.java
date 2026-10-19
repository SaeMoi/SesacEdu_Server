package com.example.sesacedu.global.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Swagger(OpenAPI) 전역 설정.
 * 접속: http://localhost:8080/swagger-ui.html
 * (외장 Tomcat WAR 배포 시: http://localhost:8080/SesacEdu/swagger-ui.html)
 *
 * Authorize 버튼에 JWT(토큰만, "Bearer " 제외)를 넣으면 모든 요청에
 * Authorization: Bearer {jwt} 로 전송된다.
 */
@Configuration
public class SwaggerConfig {

    public static final String BEARER_AUTH = "bearerAuth";

    @Bean
    public OpenAPI sesacEduOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("SesacEdu API")
                        .description("새싹에듀 백엔드 API 문서")
                        .version("v1"))
                .components(new Components()
                        .addSecuritySchemes(BEARER_AUTH, new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")))
                // ★ 추가: 전역 보안 요구. 이게 있어야 Authorize 토큰이 모든 요청에 자동으로 붙는다.
                //   (특정 API 만 적용하려면 이 줄을 빼고 컨트롤러에 @SecurityRequirement(name = BEARER_AUTH) 사용)
                .addSecurityItem(new SecurityRequirement().addList(BEARER_AUTH));
    }
}