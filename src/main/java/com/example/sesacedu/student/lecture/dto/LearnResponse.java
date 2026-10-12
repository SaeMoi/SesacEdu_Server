package com.example.sesacedu.student.lecture.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 강의 수강 화면 응답. GET /lectures/{Lid}/learn
 *
 * 영상의 실제 저장 경로는 넣지 않는다. 대신 재생을 요청할 API 주소(videoUrl)를 준다.
 * 저장 경로를 내려보내면 그 주소를 받은 사람이 권한 검사를 건너뛰고 파일을 바로
 * 가져갈 수 있다. 재생 API 가 호출될 때 수강 권한을 다시 확인한다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LearnResponse {

    private final int lectureId;
    private final String name;
    private final String videoUrl;
    private final Integer duration;
    private final Integer position;
    private final boolean done;
    private final String materialUrl;
    private final String assignmentUrl;

    public LearnResponse(int lectureId, String name, String videoUrl, Integer duration,
                         Integer position, boolean done, String materialUrl, String assignmentUrl) {
        this.lectureId = lectureId;
        this.name = name;
        this.videoUrl = videoUrl;
        this.duration = duration;
        this.position = position;
        this.done = done;
        this.materialUrl = materialUrl;
        this.assignmentUrl = assignmentUrl;
    }

    public int getLectureId() {
        return lectureId;
    }

    public String getName() {
        return name;
    }

    /** 재생을 요청할 API 주소. 파일 경로가 아니다. 영상이 없으면 생략된다. */
    public String getVideoUrl() {
        return videoUrl;
    }

    /** 영상 전체 길이(초). 아직 모르면 생략된다. */
    public Integer getDuration() {
        return duration;
    }

    /** 이어보기 위치(초). 한 번도 안 봤으면 생략된다. 수료 판정과는 별개다. */
    public Integer getPosition() {
        return position;
    }

    /** 수료 여부. enrollments.done 이다. */
    public boolean isDone() {
        return done;
    }

    public String getMaterialUrl() {
        return materialUrl;
    }

    public String getAssignmentUrl() {
        return assignmentUrl;
    }
}
