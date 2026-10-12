package com.example.sesacedu.instructor.settlement.dto;

import java.util.List;

/**
 * 정산 상세 조회 응답.
 * 월 합계는 settlements 저장값, 강의별(items) 매출은 해당 월 order_items 집계,
 * 공제/정산액은 공제율로 계산한 값.
 */
public class SettlementDetail {

    private int sid;
    private String period;
    private String status;
    private long revenue;      // 월 매출 (저장값)
    private long deduction;    // 월 공제 (저장값)
    private long amount;       // 월 정산액 (저장값)
    private String bank;       // 지급 은행
    private String account;    // 지급 계좌
    private List<Item> items;  // 강의별 내역 (집계+계산)

    public SettlementDetail() {
    }

    public int getSid() { return sid; }
    public void setSid(int sid) { this.sid = sid; }

    public String getPeriod() { return period; }
    public void setPeriod(String period) { this.period = period; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public long getRevenue() { return revenue; }
    public void setRevenue(long revenue) { this.revenue = revenue; }

    public long getDeduction() { return deduction; }
    public void setDeduction(long deduction) { this.deduction = deduction; }

    public long getAmount() { return amount; }
    public void setAmount(long amount) { this.amount = amount; }

    public String getBank() { return bank; }
    public void setBank(String bank) { this.bank = bank; }

    public String getAccount() { return account; }
    public void setAccount(String account) { this.account = account; }

    public List<Item> getItems() { return items; }
    public void setItems(List<Item> items) { this.items = items; }

    /** 강의별 정산 내역 (매출은 집계, 공제·정산액은 계산) */
    public static class Item {
        private String lectureName;
        private long revenue;
        private long deduction;
        private long amount;

        public Item() {
        }

        public Item(String lectureName, long revenue, long deduction, long amount) {
            this.lectureName = lectureName;
            this.revenue = revenue;
            this.deduction = deduction;
            this.amount = amount;
        }

        public String getLectureName() { return lectureName; }
        public void setLectureName(String lectureName) { this.lectureName = lectureName; }

        public long getRevenue() { return revenue; }
        public void setRevenue(long revenue) { this.revenue = revenue; }

        public long getDeduction() { return deduction; }
        public void setDeduction(long deduction) { this.deduction = deduction; }

        public long getAmount() { return amount; }
        public void setAmount(long amount) { this.amount = amount; }
    }
}