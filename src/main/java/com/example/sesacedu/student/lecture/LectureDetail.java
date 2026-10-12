package com.example.sesacedu.student.lecture;

public class LectureDetail {

    private final int lid;
    private final int instructorUserId;
    private final String name;
    private final String headline;
    private final String summary;
    private final String category;
    private final String level;
    private final String picture;
    private final String preview;
    private final int price;
    private final String instructorName;
    private final String instructorBio;
    private final String instructorField;
    private final String instructorCareer;

    public LectureDetail(int lid, int instructorUserId, String name, String headline, String summary,
                         String category, String level, String picture, String preview, int price,
                         String instructorName, String instructorBio, String instructorField,
                         String instructorCareer) {
        this.lid = lid;
        this.instructorUserId = instructorUserId;
        this.name = name;
        this.headline = headline;
        this.summary = summary;
        this.category = category;
        this.level = level;
        this.picture = picture;
        this.preview = preview;
        this.price = price;
        this.instructorName = instructorName;
        this.instructorBio = instructorBio;
        this.instructorField = instructorField;
        this.instructorCareer = instructorCareer;
    }

    public int getLid() {
        return lid;
    }

    public int getInstructorUserId() {
        return instructorUserId;
    }

    public String getName() {
        return name;
    }

    public String getHeadline() {
        return headline;
    }

    public String getSummary() {
        return summary;
    }

    public String getCategory() {
        return category;
    }

    public String getLevel() {
        return level;
    }

    public String getPicture() {
        return picture;
    }

    public String getPreview() {
        return preview;
    }

    public int getPrice() {
        return price;
    }

    public String getInstructorName() {
        return instructorName;
    }

    public String getInstructorBio() {
        return instructorBio;
    }

    public String getInstructorField() {
        return instructorField;
    }

    public String getInstructorCareer() {
        return instructorCareer;
    }
}
