package com.example.sesacedu.global.exception;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 모든 컨트롤러에서 올라온 예외를 한 곳에서 JSON 으로 바꾼다.
 *
 * 컨트롤러마다 try-catch 를 쓰지 않는 이유는 두 가지다. 오류 응답 형식이 한 군데서
 * 결정되고, 처리하지 않은 예외가 그대로 밖으로 나가는 일이 없어진다. 자바 예외가
 * 그대로 나가면 스택 트레이스와 SQL 문이 응답에 실려 내부 구조가 드러난다.
 *
 * 적용 범위는 강의 API(student 패키지)로 한정한다. 범위를 열어 두면 마이페이지처럼
 * 자기 오류 형식({code, message, result})과 처리 순서(토큰 먼저)를 가진 다른 패키지의
 * 예외까지 이 클래스가 가로채 응답 형식이 바뀐다.
 */
@RestControllerAdvice(basePackages = "com.example.sesacedu.student")
public class GlobalExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApi(ApiException ex) {
        return ResponseEntity.status(ex.getStatus())
                .body(new ErrorResponse(ex.getCode(), ex.getMessage()));
    }

    // /lectures/abc 처럼 숫자 자리에 글자가 온 경우다. Spring 이 int 로 바꾸다 실패한다.
    // 메시지를 그대로 내보내면 어떤 타입으로 바꾸려 했는지가 드러나므로 고정 문구로 답한다.
    // 본문 JSON 이 깨졌거나 형식이 안 맞는 경우다. 그냥 두면 스프링 기본 오류가
    // 나가면서 timestamp 와 path 가 함께 실리고, 어떤 필드에서 깨졌는지까지 드러난다.
    // 우리 형식으로 바꾸고 내용은 알려 주지 않는다.
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException ex) {
        return ResponseEntity.badRequest()
                .body(new ErrorResponse("INVALID_INPUT", "요청 본문을 읽을 수 없습니다."));
    }

    // 고유 제약을 어긴 경우다. 중복 검사를 먼저 하지만, 그 사이에 다른 요청이
    // 같은 값을 넣으면 여기로 온다. DB 오류문을 그대로 내보내면 테이블명과
    // 제약 이름이 드러나므로 409 로 바꾼다.
    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<ErrorResponse> handleDuplicate(DuplicateKeyException ex) {
        return ResponseEntity.status(409)
                .body(new ErrorResponse("DUPLICATE", "이미 등록된 내용입니다."));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return ResponseEntity.badRequest()
                .body(new ErrorResponse("INVALID_INPUT", "요청 값이 올바르지 않습니다."));
    }
}
