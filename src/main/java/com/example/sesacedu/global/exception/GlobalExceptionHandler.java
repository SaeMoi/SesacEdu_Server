package com.example.sesacedu.global.exception;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 모든 컨트롤러에서 올라온 예외를 한 곳에서 JSON 으로 바꾼다.
 *
 * 컨트롤러마다 try-catch 를 쓰지 않는 이유는 두 가지다. 오류 응답 형식이 한 군데서
 * 결정되고, 처리하지 않은 예외가 그대로 밖으로 나가는 일이 없어진다. 자바 예외가
 * 그대로 나가면 스택 트레이스와 SQL 문이 응답에 실려 내부 구조가 드러난다.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApi(ApiException ex) {
        return ResponseEntity.status(ex.getStatus())
                .body(new ErrorResponse(ex.getCode(), ex.getMessage()));
    }

    // /lectures/abc 처럼 숫자 자리에 글자가 온 경우다. Spring 이 int 로 바꾸다 실패한다.
    // 메시지를 그대로 내보내면 어떤 타입으로 바꾸려 했는지가 드러나므로 고정 문구로 답한다.
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return ResponseEntity.badRequest()
                .body(new ErrorResponse("INVALID_INPUT", "요청 값이 올바르지 않습니다."));
    }
}
