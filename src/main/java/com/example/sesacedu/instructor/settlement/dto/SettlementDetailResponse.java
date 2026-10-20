package com.example.sesacedu.instructor.settlement.dto;

import java.util.List;

/**
 * 정산 상세 응답.
 * 헤더(정산 합계·상태·계좌)는 settlements 행에서, lectures(강의별 내역)는 해당 기간 주문에서 계산.
 */
public class SettlementDetailResponse {

    private Integer sid;        // 정산 행이 아직 없는 달은 null
    private String period;
    private String status;
    private long totalAmount;   // 정산 합계 (승인·지급은 저장값, 그 외는 주문에서 계산)
    private String bank;
    private String account;
    private String payDate;     // 지급 예정/완료일 (없으면 null = "승인 후 안내")
    private int page;
    private int size;
    private long totalElements;
    private int totalPages;
    private List<SettlementLectureItem> lectures;

    public SettlementDetailResponse() {
    }

    public SettlementDetailResponse(Integer sid, String period, String status, long totalAmount,
                                    String bank, String account, String payDate,
                                    int page, int size, long totalElements, int totalPages,
                                    List<SettlementLectureItem> lectures) {
        this.sid = sid;
        this.period = period;
        this.status = status;
        this.totalAmount = totalAmount;
        this.bank = bank;
        this.account = account;
        this.payDate = payDate;
        this.page = page;
        this.size = size;
        this.totalElements = totalElements;
        this.totalPages = totalPages;
        this.lectures = lectures;
    }

    public Integer getSid() { return sid; }
    public void setSid(Integer sid) { this.sid = sid; }

    public String getPeriod() { return period; }
    public void setPeriod(String period) { this.period = period; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public long getTotalAmount() { return totalAmount; }
    public void setTotalAmount(long totalAmount) { this.totalAmount = totalAmount; }

    public String getBank() { return bank; }
    public void setBank(String bank) { this.bank = bank; }

    public String getAccount() { return account; }
    public void setAccount(String account) { this.account = account; }

    public String getPayDate() { return payDate; }
    public void setPayDate(String payDate) { this.payDate = payDate; }

    public int getPage() { return page; }
    public void setPage(int page) { this.page = page; }

    public int getSize() { return size; }
    public void setSize(int size) { this.size = size; }

    public long getTotalElements() { return totalElements; }
    public void setTotalElements(long totalElements) { this.totalElements = totalElements; }

    public int getTotalPages() { return totalPages; }
    public void setTotalPages(int totalPages) { this.totalPages = totalPages; }

    public List<SettlementLectureItem> getLectures() { return lectures; }
    public void setLectures(List<SettlementLectureItem> lectures) { this.lectures = lectures; }
}