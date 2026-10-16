package com.example.sesacedu.global.auth;

import com.example.sesacedu.global.JwtUtil;
import com.example.sesacedu.global.exception.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 토큰은 서명·만료만 확인한다. DB(users.token)는 보지 않는다.
 */
@SpringBootTest
class JwtAccessTokenVerifierTest {

    @Autowired JwtUtil jwtUtil;
    @Autowired JwtAccessTokenVerifier verifier;

    @Test
    void 서명이_맞으면_사용자를_돌려준다() {
        String token = jwtUtil.createLoginToken(31, 1);

        assertThat(verifier.verify(token).getUserId()).isEqualTo(31);
    }

    @Test
    void 다시_발급해도_이전_토큰은_만료까지_쓸_수_있다() {
        String old = jwtUtil.createLoginToken(31, 1);
        jwtUtil.createLoginToken(31, 1);

        assertThat(verifier.verify(old).getUserId()).isEqualTo(31);
    }

    @Test
    void 서명이_틀린_토큰은_401이다() {
        assertThatThrownBy(() -> verifier.verify("abc.def.ghi"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    @Test
    void 서명_뒤를_바꾼_토큰은_401이다() {
        String token = jwtUtil.createLoginToken(31, 1);
        String tampered = token.substring(0, token.length() - 2) + (token.endsWith("A") ? "BB" : "AA");

        assertThatThrownBy(() -> verifier.verify(tampered))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED));
    }
}
