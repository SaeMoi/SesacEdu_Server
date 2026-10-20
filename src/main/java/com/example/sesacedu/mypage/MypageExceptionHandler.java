package com.example.sesacedu.mypage;

import com.example.sesacedu.global.JwtUtil;
import com.example.sesacedu.global.JwtUtil.JwtAuthException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import javax.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.example.sesacedu.global.ApiResponse.*;

/**
 * mypage 하위 컨트롤러 전체의 공통 예외 처리 (다른 패키지에는 적용하지 않는다).
 *
 * - JwtAuthException (401 UNAUTHORIZED / TOKEN_EXPIRED, 403 REAUTH_REQUIRED) → { code, message, result: null }
 * - 본문이 JSON이 아니거나 깨짐 → 토큰을 먼저 보고(없으면 401), 있으면 400 INVALID_INPUT { field: "body" }
 * 본인 확인 토큰까지 먼저 봐야 하는 profile / password와 업로드 오류를 다루는 edit_prof는 자기 클래스에 처리기를 따로 둔다
 * (클래스 안의 @ExceptionHandler가 이 클래스보다 우선).
 */
// 강의 쪽 GlobalExceptionHandler(범위 없음)보다 먼저 적용되도록 우선순위를 가장 높게 둔다.
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackages = "com.example.sesacedu.mypage")
public class MypageExceptionHandler {

    private final JwtUtil jwtUtil;

    public MypageExceptionHandler(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

    @ExceptionHandler(JwtAuthException.class)
    public ResponseEntity<Map<String, Object>> jwt(JwtAuthException e) {
        return jwtError(e);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> unreadable(HttpServletRequest request) {
        try {
            jwtUtil.getUid(request);
        } catch (JwtAuthException e) {
            return jwtError(e);
        }
        List<Map<String, String>> errors = new ArrayList<>();
        errors.add(error("body", "JSON 형식이 올바르지 않습니다."));
        return invalidInput(errors);
    }
}
