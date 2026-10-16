package com.example.sesacedu.student.lecture.dto;

/**
 * 강의 Q&A 작성 요청 본문. POST /lectures/{Lid}/qna
 *
 * secret 은 Boolean 이다. 안 보내면 null 이고 Service 가 false 로 본다.
 * boolean 으로 두면 안 보냈을 때와 false 를 보냈을 때가 같아지는데,
 * 비밀글 여부는 기본값을 어느 쪽으로 둘지 분명히 해 두는 편이 낫다.
 */
public class QnaWriteRequest {

    private String title;
    private String question;
    private Boolean secret;

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }

    /** 비밀글 여부. 안 보내면 공개글이다. */
    public Boolean getSecret() {
        return secret;
    }

    public void setSecret(Boolean secret) {
        this.secret = secret;
    }
}
