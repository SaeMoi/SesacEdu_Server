package com.example.sesacedu.global.exception;

import org.springframework.http.HttpStatus;

/**
 * API에서 의도한 실패(잘못된 입력, 없는 자원 등)를 표현한다.
 * 컨트롤러가 직접 상태코드를 나누지 않고, 전역 핸들러가 JSON으로 바꾼다.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}
