package com.example.sesacedu.instructor.profile.dto;

/**
 * 강사 정보 조회 응답 / 수정 요청 공용 DTO.
 *
 * 화면 매핑:
 *   iname   = 공개 강사명
 *   bio     = 한 줄 소개
 *   career  = 강사 소개 (긴 글, CLOB)
 *   link    = 공개 연락처 / 링크
 *   bank    = 정산 은행
 *   account = 정산 계좌번호
 */
public class ProfileDto {

    private String iname;
    private String bio;
    private String career;
    private String link;
    private String bank;
    private String account;

    public ProfileDto() {
    }

    public String getIname() { return iname; }
    public void setIname(String iname) { this.iname = iname; }

    public String getBio() { return bio; }
    public void setBio(String bio) { this.bio = bio; }

    public String getCareer() { return career; }
    public void setCareer(String career) { this.career = career; }

    public String getLink() { return link; }
    public void setLink(String link) { this.link = link; }

    public String getBank() { return bank; }
    public void setBank(String bank) { this.bank = bank; }

    public String getAccount() { return account; }
    public void setAccount(String account) { this.account = account; }
}