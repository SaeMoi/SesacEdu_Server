package com.example.sesacedu.student.lecture.dto;

/**
 * 생성 결과. 새로 만들어진 것의 번호만 돌려준다.
 *
 * 만든 내용을 통째로 되돌려주지 않는 이유는 두 가지다. 프론트는 방금 보낸 값을
 * 이미 갖고 있고, 응답에 담을 필드를 늘릴수록 내보내면 안 되는 값이 섞일 길이 늘어난다.
 * 상세가 필요하면 조회 API 를 부르면 된다.
 */
public class CreatedResponse {

    private final int id;

    public CreatedResponse(int id) {
        this.id = id;
    }

    public int getId() {
        return id;
    }
}
