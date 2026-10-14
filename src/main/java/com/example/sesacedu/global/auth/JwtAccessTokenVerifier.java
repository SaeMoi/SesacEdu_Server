package com.example.sesacedu.global.auth;

import com.example.sesacedu.global.config.JwtUtil;
import com.example.sesacedu.global.exception.ApiException;
import io.jsonwebtoken.Claims;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * 인증 담당자가 만든 JwtUtil 로 토큰을 검증하고 사용자 번호를 꺼낸다.
 * JwtUtil 의 예외는 우리 응답 형식(ApiException)으로 바꾼다.
 */
@Component
public class JwtAccessTokenVerifier implements AccessTokenVerifier {

    private final JwtUtil jwtUtil;

    public JwtAccessTokenVerifier(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

    @Override
    public CurrentUser verify(String token) {
        Claims claims;
        try {
            // 서명·알고리즘·만료·토큰 종류를 전부 확인한다.
            claims = jwtUtil.parse(token);
        } catch (JwtUtil.JwtAuthException e) {
            // JwtUtil 의 예외를 전역 처리기가 아는 형태로 바꾼다.
            throw new ApiException(resolve(e.getStatus()), e.getCode(), e.getMessage());
        }

        long userId = jwtUtil.getUid(claims);

        // 수강 여부는 enrollments 로 보므로 권한(role)은 쓰지 않는다.
        return new CurrentUser((int) userId);
    }

    /** JwtUtil 이 주는 숫자 상태코드를 HttpStatus 로 옮긴다. 모르는 값은 401 로 본다. */
    private HttpStatus resolve(int status) {
        HttpStatus resolved = HttpStatus.resolve(status);
        if (resolved == null || !resolved.isError()) {
            return HttpStatus.UNAUTHORIZED;
        }
        return resolved;
    }
}
