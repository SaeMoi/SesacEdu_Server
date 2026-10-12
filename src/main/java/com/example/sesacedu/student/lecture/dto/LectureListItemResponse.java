package com.example.sesacedu.student.lecture.dto;

/**
 * 목록 한 칸에 내려보낼 값만 적은 응답 객체.
 *
 * DB 조회 결과를 그대로 JSON 으로 바꾸지 않고 이렇게 옮겨 담는 이유가 있다.
 * lectures 나 users 를 그대로 직렬화하면 나중에 컬럼이 하나 늘 때마다 응답에
 * 저절로 실린다. pw(비밀번호 해시) · phone · token 같은 것이 그렇게 새어 나간다.
 *
 * DTO 는 "내보낼 필드 목록"이다. 적지 않은 값은 나가지 않는다.
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
