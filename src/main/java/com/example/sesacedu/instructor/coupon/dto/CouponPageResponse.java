package com.example.sesacedu.instructor.coupon.dto;

import java.util.List;

/** 쿠폰 발행 목록(페이지네이션) 응답. size 는 백엔드 고정값(5). */
public class CouponPageResponse {

    private int page;
    private int size;
    private long totalElements;
    private int totalPages;
    private List<CouponListItem> coupons;

    public CouponPageResponse() {
    }

    public CouponPageResponse(int page, int size, long totalElements,
                              int totalPages, List<CouponListItem> coupons) {
        this.page = page;
        this.size = size;
        this.totalElements = totalElements;
        this.totalPages = totalPages;
        this.coupons = coupons;
    }

    public int getPage() { return page; }
    public void setPage(int page) { this.page = page; }

    public int getSize() { return size; }
    public void setSize(int size) { this.size = size; }

    public long getTotalElements() { return totalElements; }
    public void setTotalElements(long totalElements) { this.totalElements = totalElements; }

    public int getTotalPages() { return totalPages; }
    public void setTotalPages(int totalPages) { this.totalPages = totalPages; }

    public List<CouponListItem> getCoupons() { return coupons; }
    public void setCoupons(List<CouponListItem> coupons) { this.coupons = coupons; }
}