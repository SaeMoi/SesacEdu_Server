package com.example.sesacedu.instructor.lecture.dto;

/**
 * 강의 상세. 수정 화면 진입 시 기존 값을 채우는 응답.
 * picture=썸네일, video=영상, material=자료 (각 저장 경로 문자열).
 */
public class LectureDetail {

    private int lid;            // 강의번호
    private String name;        // 강의 제목
    private String category;    // 카테고리
    private String level;       // 난이도 (DB: level_)
    private String intro;       // 강의 소개
    private int price;          // 가격 (DB: lprice)
    private String status;      // draft / open / closed
    private String picture;     // 썸네일 경로
    private String video;       // 영상 경로
    private String material;    // 자료 경로

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

    public String getIntro() { return intro; }
    public void setIntro(String intro) { this.intro = intro; }

    public int getPrice() { return price; }
    public void setPrice(int price) { this.price = price; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getPicture() { return picture; }
    public void setPicture(String picture) { this.picture = picture; }

    public String getVideo() { return video; }
    public void setVideo(String video) { this.video = video; }

    public String getMaterial() { return material; }
    public void setMaterial(String material) { this.material = material; }
}