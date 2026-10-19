package com.example.sesacedu.student.lecture;

import java.time.LocalDateTime;

/**
 * 내가 낸 과제 제출물 한 행.
 *
 * assignments 는 (Lid, user_id) 가 UNIQUE 라 한 강의에 한 사람당 한 행만 있다.
 * 아직 안 냈으면 행 자체가 없고, 그때는 이 객체가 만들어지지 않는다.
 */
public class SubmissionRow {

    private final int aid;
    private final String workFilename;
    private final String note;
    private final LocalDateTime submitted;
    private final Integer score;
    private final String feedback;
    private final LocalDateTime feedbackAt;

    public SubmissionRow(int aid, String workFilename, String note, LocalDateTime submitted,
                         Integer score, String feedback, LocalDateTime feedbackAt) {
        this.aid = aid;
        this.workFilename = workFilename;
        this.note = note;
        this.submitted = submitted;
        this.score = score;
        this.feedback = feedback;
        this.feedbackAt = feedbackAt;
    }

    public int getAid() {
        return aid;
    }

    public String getWorkFilename() {
        return workFilename;
    }

    public String getNote() {
        return note;
    }

    public LocalDateTime getSubmitted() {
        return submitted;
    }

    /** 점수는 0 도 유효한 값이라 int 가 아니라 Integer 다. 미채점은 null 이다. */
    public Integer getScore() {
        return score;
    }

    public String getFeedback() {
        return feedback;
    }

    public LocalDateTime getFeedbackAt() {
        return feedbackAt;
    }
}
