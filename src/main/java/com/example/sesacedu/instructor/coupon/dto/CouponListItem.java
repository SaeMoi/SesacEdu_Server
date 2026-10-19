package com.example.sesacedu.instructor.coupon.dto;

/** 쿠폰 발행 목록의 한 행. */
public class CouponListItem {

    private int cid;
    private String lectureName;  // 강의명 (coupons.Lid → lectures.name)
    private String code;         // 쿠폰번호 (coupons.code)
    private long discount;       // 할인 금액(원)
    private String due;          // 종료일 (YYYY-MM-DD)
    private int issued;          // 발급 수
    private Integer quantity;    // 총 수량 (없으면 null = 무제한)

    public CouponListItem() {
    }

    public CouponListItem(int cid, String lectureName, String code, long discount,
                          String due, int issued, Integer quantity) {
        this.cid = cid;
        this.lectureName = lectureName;
        this.code = code;
        this.discount = discount;
        this.due = due;
        this.issued = issued;
        this.quantity = quantity;
    }

    public int getCid() { return cid; }
    public void setCid(int cid) { this.cid = cid; }

    public String getLectureName() { return lectureName; }
    public void setLectureName(String lectureName) { this.lectureName = lectureName; }

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }

    public long getDiscount() { return discount; }
    public void setDiscount(long discount) { this.discount = discount; }

    public String getDue() { return due; }
    public void setDue(String due) { this.due = due; }

    public int getIssued() { return issued; }
    public void setIssued(int issued) { this.issued = issued; }

    public Integer getQuantity() { return quantity; }
    public void setQuantity(Integer quantity) { this.quantity = quantity; }
}