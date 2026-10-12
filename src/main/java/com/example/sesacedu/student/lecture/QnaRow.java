package com.example.sesacedu.student.lecture;

import java.time.LocalDateTime;

/**
 * 강의 Q&A 한 행.
 *
 * 목록 조회와 상세 조회가 같은 형태를 쓴다. 목록에서는 본문(question)과
 * 답변(answer)을 SELECT 하지 않으므로 그 자리가 null 이다. 비밀글이 섞인 목록에서
 * 본문까지 들고 오면 응답에 넣지 않더라도 서버 메모리에는 올라오고, 나중에 누가
 * 필드를 하나 추가하는 순간 그대로 나간다. 애초에 안 가져오는 쪽이 안전하다.
 */
public class QnaRow {

    private final int qid;
    private final int userId;
    private final String nickname;
    private final String title;
    private final String question;
    private final String answer;
    private final Integer answeredBy;
    private final LocalDateTime answeredAt;
    private final boolean secret;
    private final LocalDateTime created;

    public QnaRow(int qid, int userId, String nickname, String title,
                  String question, String answer, Integer answeredBy, LocalDateTime answeredAt,
                  boolean secret, LocalDateTime created) {
        this.qid = qid;
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

    /** 질문자. 비밀글을 볼 수 있는지 판단할 때 쓴다. */
    public int getUserId() {
        return userId;
    }

    public String getNickname() {
        return nickname;
    }

    public String getTitle() {
        return title;
    }

    /** 목록 조회에서는 null 이다. */
    public String getQuestion() {
        return question;
    }

    /** 답변 전이면 null 이다. */
    public String getAnswer() {
        return answer;
    }

    public Integer getAnsweredBy() {
        return answeredBy;
    }

    public LocalDateTime getAnsweredAt() {
        return answeredAt;
    }

    /** isSecret. DB 에는 0/1 로 들어 있다. */
    public boolean isSecret() {
        return secret;
    }

    public LocalDateTime getCreated() {
        return created;
    }
}
