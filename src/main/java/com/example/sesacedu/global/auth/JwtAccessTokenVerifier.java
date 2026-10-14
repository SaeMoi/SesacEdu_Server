package com.example.sesacedu.global.auth;

import com.example.sesacedu.global.config.JwtUtil;
import com.example.sesacedu.global.exception.ApiException;
import io.jsonwebtoken.Claims;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 인증 담당자가 만든 JwtUtil 로 토큰을 검증하고 사용자 번호를 꺼낸다.
 * JwtUtil 의 예외는 우리 응답 형식(ApiException)으로 바꾼다.
 *
 * 서명이 맞아도 DB(users.token)에 저장된 토큰과 다르면 거절한다.
 * 로그인할 때 저장한 토큰만 유효하고, 로그아웃(token = NULL)이나
 * 다른 곳에서 다시 로그인하면 이전 토큰은 더 이상 쓸 수 없다.
 */
@Component
public class JwtAccessTokenVerifier implements AccessTokenVerifier {

    private final JwtUtil jwtUtil;
    private final JdbcTemplate jdbcTemplate;

    public JwtAccessTokenVerifier(JwtUtil jwtUtil, JdbcTemplate jdbcTemplate) {
        this.jwtUtil = jwtUtil;
        this.jdbcTemplate = jdbcTemplate;
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

        // DB 에 저장된 로그인 토큰과 같은지 본다. 사용자가 없거나 저장된 토큰이 없으면 거절한다.
        List<String[]> rows = jdbcTemplate.query(
                "SELECT token, status FROM users WHERE user_id = ?",
                (rs, n) -> new String[]{rs.getString("token"), rs.getString("status")},
                userId);
        if (rows.isEmpty() || rows.get(0)[0] == null || !rows.get(0)[0].equals(token)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "TOKEN_NOT_ACTIVE",
                    "로그인 정보가 만료되었습니다. 다시 로그인해 주세요.");
        }

        // 이용이 제한된 계정은 로그인 상태여도 막는다.
        if ("restricted".equals(rows.get(0)[1])) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ACCOUNT_RESTRICTED", "이용이 제한된 계정입니다.");
        }

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
