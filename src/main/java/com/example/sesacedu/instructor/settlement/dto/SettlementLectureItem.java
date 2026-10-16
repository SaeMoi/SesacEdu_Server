package com.example.sesacedu.instructor.settlement.dto;

/** 정산 상세의 강의별 한 행. revenue(매출) - deduction(공제) = amount(정산금액). */
public class SettlementLectureItem {

    private String lectureName;
    private long revenue;    // 매출 (gross)
    private long deduction;  // 공제
    private long amount;     // 정산 금액 (net)

    public SettlementLectureItem() {
    }

    public SettlementLectureItem(String lectureName, long revenue, long deduction, long amount) {
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