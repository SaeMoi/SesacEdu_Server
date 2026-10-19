package com.example.sesacedu.instructor.lecture.dto;

import java.util.List;

/**
 * 강의 목록 페이지 응답.
 * content = 현재 페이지 항목, page/size = 요청 페이지·크기,
 * totalElements = 전체 개수, totalPages = 총 페이지 수.
 */
public class LectureListPage {

    private List<LectureListItem> content;
    private int page;
    private int size;
    private long totalElements;
    private int totalPages;

    public LectureListPage(List<LectureListItem> content, int page, int size,
                           long totalElements, int totalPages) {
        this.content = content;
        this.page = page;
        this.size = size;
        this.totalElements = totalElements;
        this.totalPages = totalPages;
    }

    public List<LectureListItem> getContent() { return content; }
    public int getPage() { return page; }
    public int getSize() { return size; }
    public long getTotalElements() { return totalElements; }
    public int getTotalPages() { return totalPages; }
}