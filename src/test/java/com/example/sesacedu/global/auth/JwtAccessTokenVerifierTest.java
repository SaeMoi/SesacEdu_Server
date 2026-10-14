package com.example.sesacedu.global.auth;

import com.example.sesacedu.global.config.JwtUtil;
import com.example.sesacedu.global.exception.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 토큰은 서명이 맞고, RDS users.token 에 저장된 값과 같아야 통과한다.
 * user 31 의 token·status 를 트랜잭션 안에서 바꾸고 끝나면 롤백한다.
 */
@SpringBootTest
@Transactional
class JwtAccessTokenVerifierTest {

    @Autowired JwtUtil jwtUtil;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtAccessTokenVerifier verifier;

    @Test
    void DB에_저장된_토큰이면_사용자를_돌려준다() {
        String token = login(31);

        assertThat(verifier.verify(token).getUserId()).isEqualTo(31);
    }

    @Test
    void 로그아웃해서_DB_토큰이_비면_401이다() {
        String token = login(31);
        jdbc.update("UPDATE users SET token = NULL WHERE user_id = 31");

        assertRejected(token, HttpStatus.UNAUTHORIZED, "TOKEN_NOT_ACTIVE");
    }

    @Test
    void 다시_로그인하면_이전_토큰은_401이다() {
        String old = login(31);
        jdbc.update("UPDATE users SET token = ? WHERE user_id = 31", old + "x");

        assertRejected(old, HttpStatus.UNAUTHORIZED, "TOKEN_NOT_ACTIVE");
    }

    @Test
    void 없는_사용자의_토큰은_401이다() {
        assertRejected(jwtUtil.createLoginToken(999999, 1), HttpStatus.UNAUTHORIZED, "TOKEN_NOT_ACTIVE");
    }

    @Test
    void 제한된_계정은_403이다() {
        String token = login(31);
        jdbc.update("UPDATE users SET status = 'restricted' WHERE user_id = 31");

        assertRejected(token, HttpStatus.FORBIDDEN, "ACCOUNT_RESTRICTED");
    }

    @Test
    void 서명이_틀린_토큰은_401이다() {
        assertThatThrownBy(() -> verifier.verify("abc.def.ghi"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    /** 로그인과 같게 토큰을 만들어 users.token 에 넣는다. */
    private String login(int userId) {
        String token = jwtUtil.createLoginToken(userId, 1);
        jdbc.update("UPDATE users SET token = ? WHERE user_id = ?", token, userId);
        return token;
    }

    private void assertRejected(String token, HttpStatus status, String code) {
        assertThatThrownBy(() -> verifier.verify(token))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    ApiException ex = (ApiException) e;
                    assertThat(ex.getStatus()).isEqualTo(status);
                    assertThat(ex.getCode()).isEqualTo(code);
                });
    }
}
