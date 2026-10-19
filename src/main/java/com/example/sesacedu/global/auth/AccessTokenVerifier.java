package com.example.sesacedu.global.auth;

/**
 * 인증 담당자의 검증기를 연결하는 경계.
 * 구현체는 JWT 서명(허용 알고리즘 포함)과 만료만 확인한 뒤 CurrentUser를 반환한다.
 * 서버는 토큰을 저장하지 않으므로 DB(users.token·status)는 보지 않는다. users.token 컬럼은 남아 있지만 쓰지 않는다.
 * 실패는 401 ApiException으로 알린다.
 */
public interface AccessTokenVerifier {
    CurrentUser verify(String token);
}
