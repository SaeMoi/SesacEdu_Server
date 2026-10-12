package com.example.sesacedu.global.auth;

import org.springframework.core.MethodParameter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import com.example.sesacedu.global.exception.ApiException;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * 컨트롤러 메서드에 CurrentUser가 있으면 Authorization Bearer를 읽어 검증된 사용자만 넘긴다.
 * 헤더가 없으면 비로그인. 제시된 토큰은 반드시 공통 검증기를 통과해야 한다.
 */
@Component
public class CurrentUserArgumentResolver implements HandlerMethodArgumentResolver {

    // ObjectProvider 로 받으면 검증기 빈이 아직 없어도 서버가 뜬다.
    // 바로 주입받게 하면 인증 담당자 코드가 합쳐지기 전까지 기동 자체가 실패한다.
    private final ObjectProvider<AccessTokenVerifier> verifierProvider;

    public CurrentUserArgumentResolver(ObjectProvider<AccessTokenVerifier> verifierProvider) {
        this.verifierProvider = verifierProvider;
    }

    // 컨트롤러 메서드의 인자 타입이 CurrentUser 일 때만 이 해석기가 동작한다.
    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return CurrentUser.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter,
                                  ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest,
                                  WebDataBinderFactory binderFactory) {
        String header = webRequest.getHeader("Authorization");
        // 헤더가 아예 없으면 비로그인이다. 오류가 아니라 null 을 돌려주고,
        // Service 가 그걸 보고 guest 로 응답한다. 공개 화면도 봐야 하기 때문이다.
        if (header == null) {
            return null;
        }
        // 헤더가 있으면 형식이 맞아야 한다. Bearer 로 시작하지 않으면 401 로 거절한다.
        // 대소문자는 구분하지 않는다(regionMatches 의 첫 인자 true).
        if (!header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "인증 정보가 올바르지 않습니다.");
        }
        String token = header.substring(7).trim();
        if (token.isEmpty() || token.chars().anyMatch(Character::isWhitespace)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "인증 정보가 올바르지 않습니다.");
        }
        // 여기가 중요하다. 토큰을 디코딩해서 안에 적힌 user_id 를 그대로 믿지 않는다.
        // JWT 의 payload 는 서명을 검증하지 않으면 누구나 고쳐 쓸 수 있는 평문이다.
        // 서명·만료·발급자 확인은 전부 검증기가 한다.
        AccessTokenVerifier verifier = verifierProvider.getIfAvailable();
        // 검증기가 아직 없을 때 "일단 통과"시키면 그게 바로 인증 우회다.
        // 통과시키지 않고 503 으로 거절한다.
        if (verifier == null) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AUTH_NOT_CONFIGURED", "인증 연결이 준비되지 않았습니다.");
        }
        CurrentUser user = verifier.verify(token);
        // 검증기가 빈 값이나 0 같은 번호를 돌려주는 경우까지 막는다.
        if (user == null || user.getUserId() < 1) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "인증 정보가 올바르지 않습니다.");
        }
        return user;
    }
}
