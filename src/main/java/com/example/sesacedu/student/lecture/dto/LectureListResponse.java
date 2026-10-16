package com.example.sesacedu.student.lecture.dto;

import java.util.List;

public class LectureListResponse {

    private final List<LectureListItemResponse> lectures;
    private final PageResponse page;

    public LectureListResponse(List<LectureListItemResponse> lectures, PageResponse page) {
        this.lectures = lectures;
        this.page = page;
    }

    public List<LectureListItemResponse> getLectures() {
        return lectures;
    }

    public PageResponse getPage() {
        return page;
    }
}
