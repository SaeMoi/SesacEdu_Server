package com.example.sesacedu.instructor.qna.dto;

/**
 * Q&A 상세 (답변 작성 화면).
 * answer 가 null 이면 아직 미답변.
 */
public class QnaDetail {

    private int qid;
    private String lectureName;  // 강의명
    private String nickname;     // 질문자(학생) 닉네임
    private String title;        // 질문 제목
    private String question;     // 질문 본문
    private String answer;       // 기존 답변 (없으면 null)
    private boolean secret;      // 비밀글 여부 (isSecret)
    private String status;       // none / done
    private String created;      // 작성일 (YYYY-MM-DD)

    public QnaDetail() {
    }

    public int getQid() { return qid; }
    public void setQid(int qid) { this.qid = qid; }

    public String getLectureName() { return lectureName; }
    public void setLectureName(String lectureName) { this.lectureName = lectureName; }

    public String getNickname() { return nickname; }
    public void setNickname(String nickname) { this.nickname = nickname; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getQuestion() { return question; }
    public void setQuestion(String question) { this.question = question; }

    public String getAnswer() { return answer; }
    public void setAnswer(String answer) { this.answer = answer; }

    public boolean isSecret() { return secret; }
    public void setSecret(boolean secret) { this.secret = secret; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getCreated() { return created; }
    public void setCreated(String created) { this.created = created; }
}