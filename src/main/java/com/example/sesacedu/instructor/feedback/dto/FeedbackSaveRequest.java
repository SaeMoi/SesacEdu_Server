package com.example.sesacedu.instructor.feedback.dto;

/**
 * 피드백 저장(PUT) 요청 바디.
 */
public class FeedbackSaveRequest {

    private Integer score;    // 점수
    private String feedback;  // 피드백 내용

    public FeedbackSaveRequest() {
    }

    public Integer getScore() { return score; }
    public void setScore(Integer score) { this.score = score; }

    public String getFeedback() { return feedback; }
    public void setFeedback(String feedback) { this.feedback = feedback; }
}