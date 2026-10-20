package com.example.sesacedu.instructor.settlement.dto;

/** 정산 목록의 한 행 (월별 정산). */
public class SettlementListItem {

    private Integer sid;     // 정산 행이 아직 없는 달(pending)은 null
    private String period;   // "2026.10"
    private long amount;     // 정산 금액 (net)
    private String status;   // pending(정산 대기) / requested / approved / paid / rejected

    public SettlementListItem() {
    }

    public SettlementListItem(Integer sid, String period, long amount, String status) {
        this.sid = sid;
        this.period = period;
        this.amount = amount;
        this.status = status;
    }

    public Integer getSid() { return sid; }
    public void setSid(Integer sid) { this.sid = sid; }

    public String getPeriod() { return period; }
    public void setPeriod(String period) { this.period = period; }

    public long getAmount() { return amount; }
    public void setAmount(long amount) { this.amount = amount; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}