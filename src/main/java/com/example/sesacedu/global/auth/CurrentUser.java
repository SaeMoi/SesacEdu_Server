package com.example.sesacedu.global.auth;

/**
 * 요청에서 확인된 로그인 사용자.
 * AccessTokenVerifier에서 검증을 마친 뒤 생성한다.
 */
public class CurrentUser {

    private final int userId;

    public CurrentUser(int userId) {
        this.userId = userId;
    }

    public int getUserId() {
        return userId;
    }
}
