package com.example.sesacedu.student.lecture.dto;

/**
 * 강의 자료 응답. GET /lectures/{Lid}/material
 *
 * 저장 경로(lectures.material)는 서버가 정한 값이고, 파일명(material_filename)은
 * 올린 사람이 정한 값이다. 둘을 나눠 두면 화면에는 원본 파일명을 보여 주면서
 * 실제 경로는 감출 수 있다. downloadUrl 은 다운로드를 요청할 API 주소다.
 *
 * 원본 파일명은 올린 사람이 정하는 값이라 그대로 화면에 뿌리면 저장형 XSS 자리가
 * 된다. 프론트에서 이스케이프해 출력해야 한다.
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

    /** 화면에 보여 줄 원본 파일명. 저장 경로가 아니다. */
    public String getFilename() {
        return filename;
    }

    /** 다운로드를 요청할 API 주소. 이 주소를 호출할 때 수강 권한을 다시 확인한다. */
    public String getDownloadUrl() {
        return downloadUrl;
    }
}
