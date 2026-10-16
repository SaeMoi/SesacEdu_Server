package com.example.sesacedu.instructor.feedback.dto;

/**
 * 과제 상세 조회 응답.
 * feedback 이 null 이면 아직 피드백 미작성.
 */
public class AssignmentDetail {

    private int aid;
    private String nickname;      // 학생 닉네임
    private String lectureName;   // 강의명 (= 과제 제목)
    private String content;       // 제출 설명 텍스트
    private String work;          // 제출 파일 원본명
    private String workPath;      // 제출 파일 경로 (다운로드용)
    private Integer score;        // 점수 (없으면 null)
    private String feedback;      // 피드백 내용 (없으면 null)
    private String submitted;     // 제출일 (YYYY-MM-DD)

    public AssignmentDetail() {
    }

    public int getAid() { return aid; }
    public void setAid(int aid) { this.aid = aid; }

    public String getNickname() { return nickname; }
    public void setNickname(String nickname) { this.nickname = nickname; }

    public String getLectureName() { return lectureName; }
    public void setLectureName(String lectureName) { this.lectureName = lectureName; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getWork() { return work; }
    public void setWork(String work) { this.work = work; }

    public String getWorkPath() { return workPath; }
    public void setWorkPath(String workPath) { this.workPath = workPath; }

    public Integer getScore() { return score; }
    public void setScore(Integer score) { this.score = score; }

    public String getFeedback() { return feedback; }
    public void setFeedback(String feedback) { this.feedback = feedback; }

    public String getSubmitted() { return submitted; }
    public void setSubmitted(String submitted) { this.submitted = submitted; }
}