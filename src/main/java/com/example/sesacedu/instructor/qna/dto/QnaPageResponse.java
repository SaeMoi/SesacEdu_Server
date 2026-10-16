package com.example.sesacedu.instructor.qna.dto;

import java.util.List;

/**
 * 강사 Q&A 목록(페이지네이션) 응답. size 는 백엔드 고정값(5).
 */
public class QnaPageResponse {

    private int page;
    private int size;
    private long totalElements;
    private int totalPages;
    private List<QnaListItem> qnaList;

    public QnaPageResponse() {
    }

    public QnaPageResponse(int page, int size, long totalElements,
                           int totalPages, List<QnaListItem> qnaList) {
        this.page = page;
        this.size = size;
        this.totalElements = totalElements;
        this.totalPages = totalPages;
        this.qnaList = qnaList;
    }

    public int getPage() { return page; }
    public void setPage(int page) { this.page = page; }

    public int getSize() { return size; }
    public void setSize(int size) { this.size = size; }

    public long getTotalElements() { return totalElements; }
    public void setTotalElements(long totalElements) { this.totalElements = totalElements; }

    public int getTotalPages() { return totalPages; }
    public void setTotalPages(int totalPages) { this.totalPages = totalPages; }

    public List<QnaListItem> getQnaList() { return qnaList; }
    public void setQnaList(List<QnaListItem> qnaList) { this.qnaList = qnaList; }
}