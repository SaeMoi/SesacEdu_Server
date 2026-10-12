package com.example.sesacedu.instructor.lecture.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 강의 상세. 수정 화면 진입 시 기존 값을 채우는 응답.
 * picture=썸네일 경로. 영상·자료는 경로가 아니라 원본 파일명만 내려준다
 * (video_filename / material_filename).
 *
 * DB 매핑(rebuild_oracle.sql 기준):
 *   lid              -> lectures.Lid
 *   level            -> lectures.lvl
 *   headline         -> lectures.headline
 *   summary          -> lectures.summary
 *   price            -> lectures.Lprice
 *   videoFilename    -> lectures.video_filename
 *   materialFilename -> lectures.material_filename
 */
public class LectureDetail {

    private int lid;            // 강의번호 (DB: Lid)
    private String name;        // 강의 제목
    private String category;    // 카테고리
    private String level;       // 난이도 (DB: lvl)
    private String headline;    // 강의 한 줄 소개
    private String summary;     // 강의 소개 본문
    private int price;          // 가격 (DB: Lprice)
    private String status;      // draft / open / closed
    private String picture;     // 썸네일 경로

    @JsonProperty("video_filename")
    private String videoFilename;     // 영상 원본 파일명 (DB: video_filename)

    @JsonProperty("material_filename")
    private String materialFilename;  // 자료 원본 파일명 (DB: material_filename)

    public LectureDetail() {
    }

    public int getLid() { return lid; }
    public void setLid(int lid) { this.lid = lid; }

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

    public int getPrice() { return price; }
    public void setPrice(int price) { this.price = price; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getPicture() { return picture; }
    public void setPicture(String picture) { this.picture = picture; }

    public String getVideoFilename() { return videoFilename; }
    public void setVideoFilename(String videoFilename) { this.videoFilename = videoFilename; }

    public String getMaterialFilename() { return materialFilename; }
    public void setMaterialFilename(String materialFilename) { this.materialFilename = materialFilename; }
}