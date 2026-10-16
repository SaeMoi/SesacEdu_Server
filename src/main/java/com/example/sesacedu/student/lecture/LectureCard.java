package com.example.sesacedu.student.lecture;

/**
 * 목록 카드에 필요한 DB 조회 결과.
 * JSON 이름과 컬럼명을 섞지 않기 위해 서비스/DTO가 따로 이름을 붙인다.
 */
public class LectureCard {

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

    public LectureCard(int lid, String name, String headline, String picture,
                       String instructor, String category, String level, int price) {
        this(lid, name, headline, picture, instructor, category, level, price, null, 0);
    }

    public LectureCard(int lid, String name, String headline, String picture,
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
