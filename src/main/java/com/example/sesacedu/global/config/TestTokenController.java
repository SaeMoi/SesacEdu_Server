package com.example.sesacedu.global.config;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * ⚠️ 테스트/개발 전용 토큰 발급기.
 *   로그인·비밀번호 검증 없이 원하는 user_id·권한으로 로그인 토큰을 만들어 준다.
 *   Swagger 에서 Authorization 헤더에 넣을 토큰을 손쉽게 얻기 위한 용도.
 *
 *   ❗운영 배포 전 반드시 제거하거나 dev 프로파일로 제한할 것.
 *     (아무나 관리자 토큰을 발급받을 수 있으므로 그대로 두면 심각한 인증 우회가 됨)
 *     제한하려면 클래스에 @org.springframework.context.annotation.Profile("dev") 추가 +
 *     application-dev.yaml 에서만 활성화.
 */
@Tag(name = "⚠️ 테스트용 토큰 발급 (개발 전용)")
@RestController
// 실제 JWT 검증기가 연결된 뒤로 이 엔드포인트가 발급한 토큰은 모든 API 에서 통한다.
// 아무나 userId 를 넣어 남의 토큰을 받을 수 있으므로 기본은 꺼 둔다.
// 로컬에서 쓰려면 .env 에 DEV_TOKEN_ENABLED=true 를 넣는다. 배포 환경에는 넣지 않는다.
@ConditionalOnProperty(name = "DEV_TOKEN_ENABLED", havingValue = "true")
public class TestTokenController {

    private final JwtUtil jwtUtil;

    public TestTokenController(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

    /**
     * 로그인 토큰 발급.
     * @param userId users.user_id (토큰 sub 클레임)
     * @param auth   권한 0~4 (0 일반 / 1 수강생 / 2 강사 / 3 관리자 / 4 강사신청대기). 기본 2(강사)
     */
    @Operation(summary = "테스트 토큰 발급",
            description = "로그인 없이 user_id·권한으로 로그인 토큰을 발급한다(개발 전용). "
                    + "응답의 authorizationHeader 값을 Swagger Authorize 에 그대로 붙여넣으면 된다.")
    @GetMapping("/dev/token")
    public ResponseEntity<Map<String, Object>> issue(
            @Parameter(description = "users.user_id") @RequestParam("userId") long userId,
            @Parameter(description = "권한 0~4 (0일반/1수강생/2강사/3관리자/4신청대기)")
            @RequestParam(value = "auth", defaultValue = "2") int auth) {

        String token = jwtUtil.createLoginToken(userId, auth);   // 바뀐 시그니처: (userId, auth)
        return ResponseEntity.ok(Map.of(
                "userId", userId,
                "auth", auth,
                "role", JwtUtil.roleOf(auth),
                "token", token,
                "authorizationHeader", jwtUtil.toHeaderValue(token)   // "Bearer xxx..."
        ));
    }
}