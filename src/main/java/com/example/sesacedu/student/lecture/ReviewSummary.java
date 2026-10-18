package com.example.sesacedu.student.lecture;

public class ReviewSummary {

    private final Double rating;
    private final int reviewCount;

    public ReviewSummary(Double rating, int reviewCount) {
        this.rating = rating;
        this.reviewCount = reviewCount;
    }

    public Double getRating() {
        return rating;
    }

    public int getReviewCount() {
        return reviewCount;
    }
}
