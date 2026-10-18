package com.example.sesacedu.student.lecture.dto;

/**
 * 수강평 작성 요청 본문. POST /lectures/{Lid}/reviews
 *
 * star 는 Integer 다. int 로 두면 값을 안 보냈을 때 0 이 되어 "안 보냄"과
 * "0점을 보냄"이 구분되지 않는다. 필수값 검사를 하려면 null 이 와야 한다.
 */
public class ReviewWriteRequest {

    private Integer star;
    private String review;

    /** 1~5. 범위 검사는 Service 가 한다. DB 는 값을 제한하지 않는다. */
    public Integer getStar() {
        return star;
    }

    public void setStar(Integer star) {
        this.star = star;
    }

    public String getReview() {
        return review;
    }

    public void setReview(String review) {
        this.review = review;
    }
}
