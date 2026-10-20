package com.example.sesacedu.instructor.lecture.dto;

import java.util.List;

/**
 * 페이지네이션 응답 공통 포맷.
 * { content, page, size, totalElements, totalPages }
 *   - page : 현재 페이지(1-base)
 *   - size : 페이지당 개수
 *   - totalElements : 필터 적용 후 전체 개수
 *   - totalPages : 전체 페이지 수 (= ceil(totalElements / size), 최소 1)
 */
public class PageResponse<T> {

    private final List<T> content;
    private final int page;
    private final int size;
    private final long totalElements;
    private final int totalPages;

    public PageResponse(List<T> content, int page, int size, long totalElements) {
        this.content = content;
        this.page = page;
        this.size = size;
        this.totalElements = totalElements;
        this.totalPages = size <= 0 ? 0 : (int) Math.max(1, Math.ceil((double) totalElements / size));
    }

    public List<T> getContent() { return content; }
    public int getPage() { return page; }
    public int getSize() { return size; }
    public long getTotalElements() { return totalElements; }
    public int getTotalPages() { return totalPages; }
}