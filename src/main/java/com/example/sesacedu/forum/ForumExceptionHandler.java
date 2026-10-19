package com.example.sesacedu.forum;

import com.example.sesacedu.forum.ForumSupport.ForumException;
import com.example.sesacedu.global.JwtUtil.JwtAuthException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Collections;
import java.util.Map;

import static com.example.sesacedu.forum.ForumSupport.body;
import static com.example.sesacedu.forum.ForumSupport.error;
import static com.example.sesacedu.forum.ForumSupport.invalidInput;

/**
 * forum 패키지 컨트롤러 6개 공통 예외 → { code, message, result } 변환.
 * (다른 패키지에는 적용되지 않는다. basePackageClasses = 이 패키지)
 *
 * - JwtAuthException : 401 UNAUTHORIZED / TOKEN_EXPIRED
 * - ForumException   : 403 ACCOUNT_RESTRICTED / NOT_POST_AUTHOR / NOT_COMMENT_AUTHOR, 404 POST_NOT_FOUND / COMMENT_NOT_FOUND
 * - JSON 깨짐         : 400 INVALID_INPUT (field "body")
 * - 그 외             : 500 INTERNAL_ERROR. 내부 원인은 응답에 넣지 않고 로그로만 남긴다.
 */
// 강의 쪽 GlobalExceptionHandler(범위 없음)보다 먼저 적용되도록 우선순위를 가장 높게 둔다.
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackageClasses = ForumExceptionHandler.class)
public class ForumExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ForumExceptionHandler.class);

    @ExceptionHandler(JwtAuthException.class)
    public ResponseEntity<Map<String, Object>> jwtError(JwtAuthException e) {
        return ResponseEntity.status(e.getStatus()).body(body(e.getCode(), e.getMessage(), null));
    }

    @ExceptionHandler(ForumException.class)
    public ResponseEntity<Map<String, Object>> forumError(ForumException e) {
        return ResponseEntity.status(e.getStatus()).body(body(e.getCode(), e.getMessage(), null));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> unreadable() {
        return invalidInput(Collections.singletonList(error("body", "JSON 형식이 올바르지 않습니다.")));
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, Object>> internal(RuntimeException e) {
        log.error("forum API 처리 중 오류", e);
        return ResponseEntity.status(500).body(body("INTERNAL_ERROR", "서버 오류가 발생했습니다.", null));
    }
}
