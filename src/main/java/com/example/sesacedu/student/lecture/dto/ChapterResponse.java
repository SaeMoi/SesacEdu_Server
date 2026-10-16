package com.example.sesacedu.student.lecture.dto;

/**
 * 강의 목차 한 줄. 영상 하나 안의 구간이다.
 *
 * 화면은 목차를 누르면 영상을 startSec 로 옮긴다. 영상을 쪼갠 것이 아니라
 * 한 영상의 시작 지점과 제목만 들고 있다.
 */
public class ChapterResponse {

    private final int startSec;
    private final String title;

    public ChapterResponse(int startSec, String title) {
        this.startSec = startSec;
        this.title = title;
    }

    /** 이 구간이 시작하는 초. */
    public int getStartSec() {
        return startSec;
    }

    public String getTitle() {
        return title;
    }
}
