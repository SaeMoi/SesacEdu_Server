package com.example.sesacedu.instructor.feedback.dto;

/**
 * 과제 파일 다운로드 응답.
 *
 * 서버가 파일 바이트를 직접 내려주지 않고, 파일을 받을 수 있는 URL 을 돌려준다.
 * (S3 presigned URL 방식) 프론트는 이 url 로 파일을 내려받는다.
 */
public class AssignmentDownload {

    private String fileName;  // 원본 파일명 (다운로드 시 보일 이름)
    private String url;       // 다운로드 URL (지금은 임시, S3 붙으면 presigned URL)

    public AssignmentDownload() {
    }

    public AssignmentDownload(String fileName, String url) {
        this.fileName = fileName;
        this.url = url;
    }

    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
}