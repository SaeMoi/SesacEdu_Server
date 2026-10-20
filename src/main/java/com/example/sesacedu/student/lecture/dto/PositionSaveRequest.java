package com.example.sesacedu.student.lecture.dto;

/**
 * 이어보기 위치 저장 요청. PUT /lectures/{Lid}/learn/position
 *
 * position 을 Integer 로 받는다. int 로 받으면 값을 아예 안 보냈을 때 자동으로
 * 0 이 되어 "안 보냈다"와 "맨 앞으로 되돌렸다"가 구별되지 않는다. 그러면
 * 요청 본문이 비어 온 사고가 "0초로 저장"으로 조용히 처리된다.
 */
public class PositionSaveRequest {

    private Integer position;

    public Integer getPosition() {
        return position;
    }

    public void setPosition(Integer position) {
        this.position = position;
    }
}
