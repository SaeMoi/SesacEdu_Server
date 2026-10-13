package com.example.sesacedu.global.auth;

import com.example.sesacedu.global.config.JwtUtil;
import com.example.sesacedu.global.exception.ApiException;
import io.jsonwebtoken.Claims;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * 인증 담당자가 만든 JwtUtil 을 AccessTokenVerifier 경계에 연결한다.
 *
 * 이 클래스가 하는 일은 두 가지뿐이다.
 *   1. JwtUtil 에 검증을 맡기고 그 결과에서 사용자 번호를 꺼낸다
 *   2. JwtUtil 이 던지는 예외를 우리 응답 형식(ApiException)으로 바꾼다
 *
 * 서명·알고리즘·만료 확인은 전부 JwtUtil 이 한다. 여기서 다시 하지 않는다.
 * 검증 로직을 두 벌 만들면 어느 쪽이 기준인지 알 수 없게 되고,
 * 한쪽만 고쳐지는 순간 뚫린다.
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
            // JwtUtil 은 자기 예외를 던진다. 전역 처리기가 아는 형태로 바꿔 준다.
            // 그대로 두면 처리기를 못 만나 500 이 나가고, 그러면 "토큰이 틀렸다"와
            // "서버가 고장났다"가 구별되지 않는다.
            throw new ApiException(resolve(e.getStatus()), e.getCode(), e.getMessage());
        }

        long userId = jwtUtil.getUid(claims);

        // 번호가 비정상이면 통과시키지 않는다. 검증기가 빈 값을 돌려주는 경우까지 막는다.
        if (userId < 1 || userId > Integer.MAX_VALUE) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED",
                    "인증 정보가 올바르지 않습니다.");
        }

        // 권한(role)은 여기서 보지 않는다. 강의 API 의 수강 여부는 enrollments 행으로
        // 판정하기 때문이다. 토큰의 role 로 수강 여부를 판단하면 사지 않은 강의까지 열린다.
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
