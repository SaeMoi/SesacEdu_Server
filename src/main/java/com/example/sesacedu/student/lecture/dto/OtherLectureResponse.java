package com.example.sesacedu.student.lecture.dto;

public class OtherLectureResponse {

    private final int lid;
    private final String name;
    private final int price;

    public OtherLectureResponse(int lid, String name, int price) {
        this.lid = lid;
        this.name = name;
        this.price = price;
    }

    public int getLid() {
        return lid;
    }

    public String getName() {
        return name;
    }

    public int getPrice() {
        return price;
    }
}
