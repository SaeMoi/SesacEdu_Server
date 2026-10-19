package com.example.sesacedu.instructor.feedback.dto;

import java.util.List;

/**
 * 과제 피드백 목록(페이지네이션) 응답.
 * size 는 백엔드 고정값(5)을 그대로 내려준다.
 */
public class AssignmentPageResponse {

    private int page;
    private int size;
    private long totalElements;
    private int totalPages;
    private List<AssignmentListItem> assignments;

    public AssignmentPageResponse() {
    }

    public AssignmentPageResponse(int page, int size, long totalElements,
                                  int totalPages, List<AssignmentListItem> assignments) {
        this.page = page;
        this.size = size;
        this.totalElements = totalElements;
        this.totalPages = totalPages;
        this.assignments = assignments;
    }

    public int getPage() { return page; }
    public void setPage(int page) { this.page = page; }

    public int getSize() { return size; }
    public void setSize(int size) { this.size = size; }

    public long getTotalElements() { return totalElements; }
    public void setTotalElements(long totalElements) { this.totalElements = totalElements; }

    public int getTotalPages() { return totalPages; }
    public void setTotalPages(int totalPages) { this.totalPages = totalPages; }

    public List<AssignmentListItem> getAssignments() { return assignments; }
    public void setAssignments(List<AssignmentListItem> assignments) { this.assignments = assignments; }
}