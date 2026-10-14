package com.example.sesacedu.student.lecture.dto;

/**
 * 강의 자료 응답. GET /lectures/{Lid}/material
 *
 * filename 은 원본 파일명, downloadUrl 은 다운로드 API 주소다.
 */
public class MaterialResponse {

    private final int lectureId;
    private final String filename;
    private final String downloadUrl;

    public MaterialResponse(int lectureId, String filename, String downloadUrl) {
        this.lectureId = lectureId;
        this.filename = filename;
        this.downloadUrl = downloadUrl;
    }

    public int getLectureId() {
        return lectureId;
    }

    /** 화면에 보여 줄 원본 파일명. */
    public String getFilename() {
        return filename;
    }

    /** 다운로드를 요청할 API 주소. 이 주소를 호출할 때 수강 권한을 다시 확인한다. */
    public String getDownloadUrl() {
        return downloadUrl;
    }
}
