package com.example.sesacedu.student.lecture.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 강의 수강 화면 응답. GET /lectures/{Lid}/learn
 *
 * videoUrl 은 영상 재생 API 주소다.
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
    private final List<ChapterResponse> chapters;

    public LearnResponse(int lectureId, String name, String videoUrl, Integer duration,
                         Integer position, boolean done, String materialUrl, String assignmentUrl,
                         List<ChapterResponse> chapters) {
        this.lectureId = lectureId;
        this.name = name;
        this.videoUrl = videoUrl;
        this.duration = duration;
        this.position = position;
        this.done = done;
        this.materialUrl = materialUrl;
        this.assignmentUrl = assignmentUrl;
        this.chapters = chapters;
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

    /**
     * 강의 목차. 시작 초 순서다. 등록된 목차가 없으면 빈 배열이다.
     * 생략하지 않고 빈 배열을 주는 이유는, 화면이 "목차 없음"과 "응답 오류"를 구분하게 하려는 것이다.
     */
    public List<ChapterResponse> getChapters() {
        return chapters;
    }
}
