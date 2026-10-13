package com.example.sesacedu.student.lecture.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 수강평 목록. GET /lectures/{Lid}/reviews
 *
 * 평균 평점과 전체 건수를 같이 준다. 상세 화면의 별점 표시가 이 값을 쓰는데,
 * 목록을 페이지로 나눠 받으면 현재 페이지만으로는 평균을 낼 수 없기 때문이다.
 *
 * 공개 API 다. 로그인하지 않아도 볼 수 있다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ReviewListResponse {

    private final List<ReviewItem> reviews;
    private final PageResponse page;
    private final Double rating;
    private final int reviewCount;

    public ReviewListResponse(List<ReviewItem> reviews, PageResponse page,
                              Double rating, int reviewCount) {
        this.reviews = reviews;
        this.page = page;
        this.rating = rating;
        this.reviewCount = reviewCount;
    }

    public List<ReviewItem> getReviews() {
        return reviews;
    }

    public PageResponse getPage() {
        return page;
    }

    /** 평균 평점. 리뷰가 없으면 생략된다. 0.0 으로 내려보내면 "별 0개"로 읽힌다. */
    public Double getRating() {
        return rating;
    }

    public int getReviewCount() {
        return reviewCount;
    }

    /**
     * 수강평 한 건.
     *
     * 작성자는 닉네임만 내보낸다. 실명·메일·전화번호는 조회하지도 않는다.
     *
     * 사용자 번호는 내보내지 않는다. 수정·삭제 버튼을 띄우려면 "이게 내 글인가"만
     * 알면 되고, 그건 mine 하나로 충분하다. 번호를 주면 닉네임과 번호가 짝지어진
     * 표를 목록만 훑어서 만들 수 있고, 그 번호는 다른 API 의 인자로 쓰인다.
     */
    public static class ReviewItem {

        private final boolean mine;
        private final String nickname;
        private final int star;
        private final String review;
        private final LocalDateTime created;

        public ReviewItem(boolean mine, String nickname, int star, String review,
                          LocalDateTime created) {
            this.mine = mine;
            this.nickname = nickname;
            this.star = star;
            this.review = review;
            this.created = created;
        }

        /** 로그인한 사람이 쓴 글이면 true. 비로그인은 항상 false 다. */
        public boolean isMine() {
            return mine;
        }

        public String getNickname() {
            return nickname;
        }

        /** 1~5. */
        public int getStar() {
            return star;
        }

        /** 사용자가 쓴 본문이다. 화면에 뿌릴 때 이스케이프해야 한다. */
        public String getReview() {
            return review;
        }

        public LocalDateTime getCreated() {
            return created;
        }
    }
}
