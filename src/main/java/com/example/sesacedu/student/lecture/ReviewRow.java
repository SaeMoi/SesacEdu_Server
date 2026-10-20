package com.example.sesacedu.student.lecture;

import java.time.LocalDateTime;

/** 수강평 한 행. 작성자는 닉네임만 들고 온다. 실명(uname)과 메일은 가져오지 않는다. */
public class ReviewRow {

    private final int userId;
    private final String nickname;
    private final int star;
    private final String review;
    private final LocalDateTime created;

    public ReviewRow(int userId, String nickname, int star, String review, LocalDateTime created) {
        this.userId = userId;
        this.nickname = nickname;
        this.star = star;
        this.review = review;
        this.created = created;
    }

    public int getUserId() {
        return userId;
    }

    public String getNickname() {
        return nickname;
    }

    public int getStar() {
        return star;
    }

    public String getReview() {
        return review;
    }

    public LocalDateTime getCreated() {
        return created;
    }
}
