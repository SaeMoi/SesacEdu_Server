package com.example.sesacedu.instructor.lecture.dto;

/**
 * 강의 등록(POST) / 수정(PUT) 공용 요청 바디 — 기본 정보.
 * multipart/form-data 로 전송되며 @ModelAttribute 로 바인딩된다.
 * 썸네일은 이 DTO 가 아니라 컨트롤러의 thumbnail 파일 파라미터로 받고,
 * 영상·자료는 별도 업로드 API(/{lid}/files)로 올린다.
 *
 * DB 매핑(rebuild_oracle.sql 기준):
 *   level    -> lectures.lvl
 *   headline -> lectures.headline (강의 한 줄 소개)
 *   summary  -> lectures.summary  (강의 소개 본문)
 *   price    -> lectures.Lprice
 *
 * status 매핑:
 *   "저장하고 다음"(미완성) -> draft
 *   공개 -> open / 비공개 -> closed
 * 등록(POST) 시 status 를 안 주면 draft. open 은 영상·자료가 있어야 통과(공개 가드).
 */
public class LectureSaveRequest {

    private String name;       // 강의 제목 (필수)
    private String category;   // 카테고리 (필수)
    private String level;      // 난이도 (필수)
    private String headline;   // 강의 한 줄 소개 (필수)
    private String summary;    // 강의 소개 본문 (선택)
    private Integer price;     // 가격 (필수)
    private String status;     // draft / open / closed (선택; 등록 시 생략하면 draft)

    public LectureSaveRequest() {
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public String getLevel() { return level; }
    public void setLevel(String level) { this.level = level; }

    public String getHeadline() { return headline; }
    public void setHeadline(String headline) { this.headline = headline; }

    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }

    public Integer getPrice() { return price; }
    public void setPrice(Integer price) { this.price = price; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}