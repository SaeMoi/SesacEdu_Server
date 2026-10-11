package com.example.sesacedu.instructor.qna.dto;

/**
 * Q&A 목록의 한 행.
 * status 는 저장값이 아니라 answer 의 NULL 여부로 서버가 계산한 값.
 *   none = 미답변, done = 답변완료
 */
public class QnaListItem {

    private int qid;
    private String lectureName;   // 강의명
    private String title;         // 질문 제목
    private String status;        // none / done
    private String created;       // 작성일 (YYYY-MM-DD)

    public QnaListItem() {
    }

    public QnaListItem(int qid, String lectureName, String title, String status, String created) {
        this.qid = qid;
        this.lectureName = lectureName;
        this.title = title;
        this.status = status;
        this.created = created;
    }

    public int getQid() { return qid; }
    public void setQid(int qid) { this.qid = qid; }

    public String getLectureName() { return lectureName; }
    public void setLectureName(String lectureName) { this.lectureName = lectureName; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getCreated() { return created; }
    public void setCreated(String created) { this.created = created; }
}