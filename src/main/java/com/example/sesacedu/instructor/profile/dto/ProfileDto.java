package com.example.sesacedu.instructor.profile.dto;

/**
 * 강사 정보 조회 응답 / 수정 요청 공용 DTO.
 *
 * 화면/DB 매핑:
 *   iname          = 공개 강사명        (instructors.iname)
 *   bio            = 한 줄 소개          (instructors.bio)
 *   career         = 강사 소개(CLOB)     (instructors.career)
 *   field          = 전문 분야          (instructors.field)
 *   link           = 공개 연락처/링크    (instructors.contact)   ← 컬럼명 주의
 *   bank           = 정산 은행          (instructors.bank)
 *   account        = 정산 계좌번호       (instructors.account)
 *   careerFilename = 경력 증빙 원본 파일명 (instructors.career_filename) — 조회 응답 전용
 *
 * 수정(PUT)은 multipart/form-data: 위 텍스트 필드 + file(경력 파일, 선택).
 * careerFilename 은 업로드 파일에서 서버가 채우므로 요청 바인딩에는 쓰이지 않는다.
 */
public class ProfileDto {

    private String iname;
    private String bio;
    private String career;
    private String field;
    private String link;
    private String bank;
    private String account;
    private String careerFilename;

    public ProfileDto() {
    }

    public String getIname() { return iname; }
    public void setIname(String iname) { this.iname = iname; }

    public String getBio() { return bio; }
    public void setBio(String bio) { this.bio = bio; }

    public String getCareer() { return career; }
    public void setCareer(String career) { this.career = career; }

    public String getField() { return field; }
    public void setField(String field) { this.field = field; }

    public String getLink() { return link; }
    public void setLink(String link) { this.link = link; }

    public String getBank() { return bank; }
    public void setBank(String bank) { this.bank = bank; }

    public String getAccount() { return account; }
    public void setAccount(String account) { this.account = account; }

    public String getCareerFilename() { return careerFilename; }
    public void setCareerFilename(String careerFilename) { this.careerFilename = careerFilename; }
}