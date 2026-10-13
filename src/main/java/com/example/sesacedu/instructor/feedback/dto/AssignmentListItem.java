package com.example.sesacedu.instructor.feedback.dto;

/**
 * 과제 피드백 목록의 한 행.
 * feedbackStatus 는 저장값이 아니라 feedback 의 NULL 여부로 서버가 계산한 값. none = 미작성, done = 작성완료
 */
public class AssignmentListItem {

    private int aid;              // 과제(제출) 번호
    private int lectureId;        // 강의 번호 (flat 목록이라 어느 강의인지 구분)
    private String nickname;      // 학생 닉네임
    private String lectureName;   // 강의명
    private String work;          // 원본 파일명 (work_filename)
    private String submitted;     // 제출일 (YYYY-MM-DD)
    private String feedbackStatus; // none / done

    public AssignmentListItem() {
    }

    public AssignmentListItem(int aid, int lectureId, String nickname, String lectureName,
                              String work, String submitted, String feedbackStatus) {
        this.aid = aid;
        this.lectureId = lectureId;
        this.nickname = nickname;
        this.lectureName = lectureName;
        this.work = work;
        this.submitted = submitted;
        this.feedbackStatus = feedbackStatus;
    }

    public int getAid() { return aid; }
    public void setAid(int aid) { this.aid = aid; }

    public int getLectureId() { return lectureId; }
    public void setLectureId(int lectureId) { this.lectureId = lectureId; }

    public String getNickname() { return nickname; }
    public void setNickname(String nickname) { this.nickname = nickname; }

    public String getLectureName() { return lectureName; }
    public void setLectureName(String lectureName) { this.lectureName = lectureName; }

    public String getWork() { return work; }
    public void setWork(String work) { this.work = work; }

    public String getSubmitted() { return submitted; }
    public void setSubmitted(String submitted) { this.submitted = submitted; }

    public String getFeedbackStatus() { return feedbackStatus; }
    public void setFeedbackStatus(String feedbackStatus) { this.feedbackStatus = feedbackStatus; }
}