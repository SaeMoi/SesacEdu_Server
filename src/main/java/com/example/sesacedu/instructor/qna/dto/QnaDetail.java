package com.example.sesacedu.instructor.qna.dto;

/**
 * Q&A 상세 조회 응답.
 * answer 가 null 이면 아직 미답변.
 */
public class QnaDetail {

    private int qid;
    private String nickname;      // 질문자 닉네임
    private String lectureName;   // 강의명
    private String title;         // 질문 제목
    private String question;      // 질문 내용
    private String answer;        // 답변 내용 (없으면 null)
    private String created;       // 작성일 (YYYY-MM-DD)

    public QnaDetail() {
    }

    public int getQid() { return qid; }
    public void setQid(int qid) { this.qid = qid; }

    public String getNickname() { return nickname; }
    public void setNickname(String nickname) { this.nickname = nickname; }

    public String getLectureName() { return lectureName; }
    public void setLectureName(String lectureName) { this.lectureName = lectureName; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getQuestion() { return question; }
    public void setQuestion(String question) { this.question = question; }

    public String getAnswer() { return answer; }
    public void setAnswer(String answer) { this.answer = answer; }

    public String getCreated() { return created; }
    public void setCreated(String created) { this.created = created; }
}