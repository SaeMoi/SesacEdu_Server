package com.example.sesacedu.instructor.lecture.dto;

/**
 * 강의 목록 1행.
 * studentCount 는 enrollments COUNT 결과(저장 컬럼 아님).
 */
public class LectureListItem {

    private int lid;            // 강의번호
    private String name;        // 강의 제목
    private String status;      // draft / open / closed
    private int studentCount;   // 수강생 수

    public LectureListItem(int lid, String name, String status, int studentCount) {
        this.lid = lid;
        this.name = name;
        this.status = status;
        this.studentCount = studentCount;
    }

    public int getLid() { return lid; }
    public String getName() { return name; }
    public String getStatus() { return status; }
    public int getStudentCount() { return studentCount; }
}