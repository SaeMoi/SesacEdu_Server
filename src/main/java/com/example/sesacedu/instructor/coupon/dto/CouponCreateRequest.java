package com.example.sesacedu.instructor.coupon.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 쿠폰 발행 요청 (POST /instructor/coupon, JSON).
 * 웹 강사센터 쿠폰 발행 화면이 보내는 그대로: { Lid, cname, discount, due, quantity }
 *
 * DB 매핑(ERD 2026-10-15 coupons):
 *   Lid      -> coupons.Lid      (NULL 이면 전체 강의)
 *   cname    -> coupons.cname
 *   discount -> coupons.discount (원)
 *   due      -> coupons.due      (yyyy-MM-dd, 그날 23:59:59 까지)
 *   quantity -> coupons.quantity (발급 가능 총수량)
 *   code, minAmount 는 화면에 없다. 비우면 서버가 정한다.
 */
public class CouponCreateRequest {

    @JsonProperty("Lid")
    private Long lid;
    private String cname;
    private Long discount;
    private String due;
    private Integer quantity;
    private String code;
    private Long minAmount;

    public Long getLid() { return lid; }
    public void setLid(Long lid) { this.lid = lid; }

    public String getCname() { return cname; }
    public void setCname(String cname) { this.cname = cname; }

    public Long getDiscount() { return discount; }
    public void setDiscount(Long discount) { this.discount = discount; }

    public String getDue() { return due; }
    public void setDue(String due) { this.due = due; }

    public Integer getQuantity() { return quantity; }
    public void setQuantity(Integer quantity) { this.quantity = quantity; }

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }

    public Long getMinAmount() { return minAmount; }
    public void setMinAmount(Long minAmount) { this.minAmount = minAmount; }
}
