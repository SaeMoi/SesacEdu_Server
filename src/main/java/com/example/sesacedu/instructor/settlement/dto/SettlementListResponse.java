package com.example.sesacedu.instructor.settlement.dto;

import java.util.List;

/**
 * 정산 목록 조회 응답. 상단 요약 + 월별 정산 리스트.
 */
public class SettlementListResponse {

    private long totalRevenue;    // 총 매출 (전체 정산 revenue 합)
    private long upcomingAmount;  // 정산 예정액 (requested 상태 amount 합)
    private String latestStatus;  // 최신 정산 상태
    private List<Item> settlements;

    public SettlementListResponse() {
    }

    public SettlementListResponse(long totalRevenue, long upcomingAmount,
                                  String latestStatus, List<Item> settlements) {
        this.totalRevenue = totalRevenue;
        this.upcomingAmount = upcomingAmount;
        this.latestStatus = latestStatus;
        this.settlements = settlements;
    }

    public long getTotalRevenue() { return totalRevenue; }
    public void setTotalRevenue(long totalRevenue) { this.totalRevenue = totalRevenue; }

    public long getUpcomingAmount() { return upcomingAmount; }
    public void setUpcomingAmount(long upcomingAmount) { this.upcomingAmount = upcomingAmount; }

    public String getLatestStatus() { return latestStatus; }
    public void setLatestStatus(String latestStatus) { this.latestStatus = latestStatus; }

    public List<Item> getSettlements() { return settlements; }
    public void setSettlements(List<Item> settlements) { this.settlements = settlements; }

    /** 월별 정산 한 행 */
    public static class Item {
        private int sid;
        private String period;
        private long amount;
        private String status;

        public Item() {
        }

        public Item(int sid, String period, long amount, String status) {
            this.sid = sid;
            this.period = period;
            this.amount = amount;
            this.status = status;
        }

        public int getSid() { return sid; }
        public void setSid(int sid) { this.sid = sid; }

        public String getPeriod() { return period; }
        public void setPeriod(String period) { this.period = period; }

        public long getAmount() { return amount; }
        public void setAmount(long amount) { this.amount = amount; }

        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
    }
}