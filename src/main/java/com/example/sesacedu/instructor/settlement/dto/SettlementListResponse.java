package com.example.sesacedu.instructor.settlement.dto;

import java.util.List;

/**
 * 정산 목록 응답.
 * 상단 요약(totalRevenue/upcomingAmount/latestStatus)은 가장 최근 정산 1건 기준 — 페이지와 무관.
 * settlements 목록은 페이지당 5개 고정.
 */
public class SettlementListResponse {

    private long totalRevenue;    // 최근 정산 매출 (gross)
    private long upcomingAmount;  // 최근 정산 금액 (net)
    private String latestStatus;  // 최근 정산 상태
    private int page;
    private int size;
    private long totalElements;
    private int totalPages;
    private List<SettlementListItem> settlements;

    public SettlementListResponse() {
    }

    public SettlementListResponse(long totalRevenue, long upcomingAmount, String latestStatus,
                                  int page, int size, long totalElements, int totalPages,
                                  List<SettlementListItem> settlements) {
        this.totalRevenue = totalRevenue;
        this.upcomingAmount = upcomingAmount;
        this.latestStatus = latestStatus;
        this.page = page;
        this.size = size;
        this.totalElements = totalElements;
        this.totalPages = totalPages;
        this.settlements = settlements;
    }

    public long getTotalRevenue() { return totalRevenue; }
    public void setTotalRevenue(long totalRevenue) { this.totalRevenue = totalRevenue; }

    public long getUpcomingAmount() { return upcomingAmount; }
    public void setUpcomingAmount(long upcomingAmount) { this.upcomingAmount = upcomingAmount; }

    public String getLatestStatus() { return latestStatus; }
    public void setLatestStatus(String latestStatus) { this.latestStatus = latestStatus; }

    public int getPage() { return page; }
    public void setPage(int page) { this.page = page; }

    public int getSize() { return size; }
    public void setSize(int size) { this.size = size; }

    public long getTotalElements() { return totalElements; }
    public void setTotalElements(long totalElements) { this.totalElements = totalElements; }

    public int getTotalPages() { return totalPages; }
    public void setTotalPages(int totalPages) { this.totalPages = totalPages; }

    public List<SettlementListItem> getSettlements() { return settlements; }
    public void setSettlements(List<SettlementListItem> settlements) { this.settlements = settlements; }
}