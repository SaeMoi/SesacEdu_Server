package com.example.sesacedu.global.auth;

/**
 * 인증 담당자의 검증기를 연결하는 경계.
 * 구현체는 서명·허용 알고리즘·만료·발급자/대상·계정 상태 및 폐기 정책을
 * 확인한 뒤 CurrentUser를 반환한다. 실패는 401/403 ApiException으로 알린다.
 * 단순 디코딩이나 DB 문자열 일치만으로 구현하지 않는다.
 */
public interface AccessTokenVerifier {
    CurrentUser verify(String token);
}
