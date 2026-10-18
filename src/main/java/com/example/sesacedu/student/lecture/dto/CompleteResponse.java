package com.example.sesacedu.student.lecture.dto;

/**
 * 수료 처리 결과. POST /lectures/{Lid}/complete
 *
 * 수료 여부와 그 판단에 쓴 값을 함께 준다. 화면에서 "조금 더 보셔야 합니다"를
 * 안내할 때 몇 초가 남았는지 보여 줄 수 있어야 한다. 서버만 아는 기준으로
 * 거절하고 이유를 주지 않으면 사용자는 무엇을 해야 하는지 알 수 없다.
 */
public class CompleteResponse {

    private final int lectureId;
    private final boolean done;
    private final Integer position;
    private final Integer duration;
    private final int requiredPosition;

    public CompleteResponse(int lectureId, boolean done, Integer position,
                            Integer duration, int requiredPosition) {
        this.lectureId = lectureId;
        this.done = done;
        this.position = position;
        this.duration = duration;
        this.requiredPosition = requiredPosition;
    }

    public int getLectureId() {
        return lectureId;
    }

    /** 이 호출 뒤의 수료 여부. */
    public boolean isDone() {
        return done;
    }

    /** 저장돼 있는 이어보기 위치(초). */
    public Integer getPosition() {
        return position;
    }

    /** 영상 전체 길이(초). 등록돼 있지 않으면 null 이다. */
    public Integer getDuration() {
        return duration;
    }

    /** 수료로 인정되는 최소 위치(초). 길이가 없으면 0 이다. */
    public int getRequiredPosition() {
        return requiredPosition;
    }
}
