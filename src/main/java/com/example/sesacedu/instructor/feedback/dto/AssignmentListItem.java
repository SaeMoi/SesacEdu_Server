package com.example.sesacedu.instructor.feedback.dto;

/**
 * 과제 피드백 목록의 한 행.
 * feedbackStatus 는 저장값이 아니라 feedback 의 NULL 여부로 서버가 계산한 값.
 *   none = 미작성, done = 작성완료
 */
public class AssignmentListItem {

    private int aid;              // 과제(제출) 번호
    private String nickname;      // 학생 닉네임
    private String lectureName;   // 강의명 (= 과제 제목)
    private String submitted;     // 제출일 (YYYY-MM-DD)
    private String feedbackStatus; // none / done

    public AssignmentListItem() {
    }

    public AssignmentListItem(int aid, String nickname, String lectureName,
                              String submitted, String feedbackStatus) {
        this.aid = aid;
        this.nickname = nickname;
        this.lectureName = lectureName;
        this.submitted = submitted;
        this.feedbackStatus = feedbackStatus;
    }

    public int getAid() { return aid; }
    public void setAid(int aid) { this.aid = aid; }

    public String getNickname() { return nickname; }
    public void setNickname(String nickname) { this.nickname = nickname; }

    public String getLectureName() { return lectureName; }
    public void setLectureName(String lectureName) { this.lectureName = lectureName; }

    public String getSubmitted() { return submitted; }
    public void setSubmitted(String submitted) { this.submitted = submitted; }

    public String getFeedbackStatus() { return feedbackStatus; }
    public void setFeedbackStatus(String feedbackStatus) { this.feedbackStatus = feedbackStatus; }
}