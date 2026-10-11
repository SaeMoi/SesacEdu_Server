package com.example.sesacedu.instructor.qna.dto;

/**
 * 답변 저장(PUT) 요청 바디.
 */
public class AnswerSaveRequest {

    private String answer;  // 답변 내용

    public AnswerSaveRequest() {
    }

    public String getAnswer() { return answer; }
    public void setAnswer(String answer) { this.answer = answer; }
}