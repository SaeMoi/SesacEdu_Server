package com.example.sesacedu.student.lecture.dto;

/**
 * 과제 제출·수정 요청 본문. POST · PUT /lectures/{Lid}/assignments
 *
 * 파일 자체는 여기로 오지 않는다. 업로드는 별도 API 가 맡고, 이 API 는 업로드가
 * 끝난 뒤 받은 저장 경로(work)와 원본 파일명(workFilename)을 기록한다.
 *
 * Jackson 이 JSON 을 이 객체로 바꾸려면 기본 생성자와 setter 가 필요하다.
 * 그래서 응답 DTO 와 달리 final 을 쓰지 않는다.
 *
 * 받은 값을 그대로 저장한다. 서버에서 HTML 태그를 지우지 않는다.
 * 지우면 사용자가 쓴 원문이 사라지고, 무엇을 지울지 규칙을 서버와 화면이 따로
 * 갖게 된다. 이스케이프는 출력하는 쪽의 일이다.
 */
public class AssignmentSubmitRequest {

    private String work;
    private String workFilename;
    private String note;

    public String getWork() {
        return work;
    }

    public void setWork(String work) {
        this.work = work;
    }

    public String getWorkFilename() {
        return workFilename;
    }

    public void setWorkFilename(String workFilename) {
        this.workFilename = workFilename;
    }

    /** 제출자가 적는 메모. DB 의 Wnote 다. */
    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }
}
