package com.example.sesacedu.instructor.settlement.dto;

/** 정산 목록의 한 행 (월별 정산). */
public class SettlementListItem {

    private int sid;
    private String period;   // "2026-10" (7자)
    private long amount;     // 정산 금액 (net)
    private String status;   // requested / approved / paid / rejected

    public SettlementListItem() {
    }

    public SettlementListItem(int sid, String period, long amount, String status) {
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