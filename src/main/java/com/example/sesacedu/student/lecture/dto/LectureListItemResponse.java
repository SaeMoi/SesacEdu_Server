package com.example.sesacedu.student.lecture.dto;

/**
 * 목록 한 칸에 내려보낼 값만 적은 응답 객체.
 *
 * 강의 카드에 필요한 값만 담는다.
 */
public class LectureListItemResponse {

    private final int lid;
    private final String name;
    private final String headline;
    private final String picture;
    private final String instructor;
    private final String category;
    private final String level;
    private final int price;
    private final Double rating;
    private final int reviewCount;

    public LectureListItemResponse(int lid, String name, String headline, String picture,
                                   String instructor, String category, String level, int price,
                                   Double rating, int reviewCount) {
        this.lid = lid;
        this.name = name;
        this.headline = headline;
        this.picture = picture;
        this.instructor = instructor;
        this.category = category;
        this.level = level;
        this.price = price;
        this.rating = rating;
        this.reviewCount = reviewCount;
    }

    public Double getRating() { return rating; }
    public int getReviewCount() { return reviewCount; }

    public int getLid() {
        return lid;
    }

    public String getName() {
        return name;
    }

    public String getHeadline() {
        return headline;
    }

    public String getPicture() {
        return picture;
    }

    public String getInstructor() {
        return instructor;
    }

    public String getCategory() {
        return category;
    }

    public String getLevel() {
        return level;
    }

    public int getPrice() {
        return price;
    }
}
