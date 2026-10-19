package com.example.sesacedu.student.lecture.dto;

/**
 * 페이지 정보. 프론트가 페이지 버튼을 그리는 데 쓴다.
 *
 * current 현재 페이지 / size 한 페이지 개수 / total 전체 건수 / totalPages 전체 페이지 수.
 * 전체 건수를 같이 주지 않으면 프론트가 마지막 페이지를 알 수 없다.
 */
public class PageResponse {

    private final int current;
    private final int size;
    private final int total;
    private final int totalPages;

    public PageResponse(int current, int size, int total, int totalPages) {
        this.current = current;
        this.size = size;
        this.total = total;
        this.totalPages = totalPages;
    }

    public int getCurrent() {
        return current;
    }

    public int getSize() {
        return size;
    }

    public int getTotal() {
        return total;
    }

    public int getTotalPages() {
        return totalPages;
    }
}
