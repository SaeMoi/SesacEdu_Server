package com.example.sesacedu.student.lecture.dto;

public class InstructorPublicResponse {

    private final int instructorId;
    private final String iname;
    private final String catchphrase;
    private final String bio;
    private final String field;
    private final String career;

    public InstructorPublicResponse(int instructorId, String iname, String catchphrase,
                                    String bio, String field, String career) {
        this.instructorId = instructorId;
        this.iname = iname;
        this.catchphrase = catchphrase;
        this.bio = bio;
        this.field = field;
        this.career = career;
    }

    public int getInstructorId() {
        return instructorId;
    }

    public String getIname() {
        return iname;
    }

    public String getCatchphrase() {
        return catchphrase;
    }

    public String getBio() {
        return bio;
    }

    public String getField() {
        return field;
    }

    public String getCareer() {
        return career;
    }
}
