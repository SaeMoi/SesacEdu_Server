package com.example.sesacedu.student.lecture.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;

/**
 * 강의 Q&A 상세. GET /lectures/{Lid}/qna/{qnaid}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class QnaDetailResponse {

    private final int qid;
    private final int lectureId;
    private final int userId;
    private final String nickname;
    private final String title;
    private final String question;
    private final String answer;
    private final Integer answeredBy;
    private final LocalDateTime answeredAt;
    private final boolean secret;
    private final LocalDateTime created;

    public QnaDetailResponse(int qid, int lectureId, int userId, String nickname,
                             String title, String question, String answer,
                             Integer answeredBy, LocalDateTime answeredAt,
                             boolean secret, LocalDateTime created) {
        this.qid = qid;
        this.lectureId = lectureId;
        this.userId = userId;
        this.nickname = nickname;
        this.title = title;
        this.question = question;
        this.answer = answer;
        this.answeredBy = answeredBy;
        this.answeredAt = answeredAt;
        this.secret = secret;
        this.created = created;
    }

    public int getQid() {
        return qid;
    }

    public int getLectureId() {
        return lectureId;
    }

    /** 질문자 user_id. 수정·삭제 버튼을 보여 줄지 판단하는 데 쓴다. */
    public int getUserId() {
        return userId;
    }

    public String getNickname() {
        return nickname;
    }

    public String getTitle() {
        return title;
    }

    public String getQuestion() {
        return question;
    }

    /** 답변 본문. 답변 전이면 생략된다. */
    public String getAnswer() {
        return answer;
    }

    /** 답변한 강사의 user_id. 답변 전이면 생략된다. */
    public Integer getAnsweredBy() {
        return answeredBy;
    }

    public LocalDateTime getAnsweredAt() {
        return answeredAt;
    }

    public boolean isSecret() {
        return secret;
    }

    public LocalDateTime getCreated() {
        return created;
    }
}
