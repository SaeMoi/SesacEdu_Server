package com.example.sesacedu.instructor.profile;

import com.example.sesacedu.instructor.profile.dto.ProfileDto;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 강사 정보 관리 JDBC. instructors 테이블 1행(본인)을 다룬다.
 * 주의: API 필드 link 는 DB 컬럼 contact 에 매핑된다 (instructors 에 link 컬럼 없음).
 */
@Repository
public class ProfileRepository {

    private final JdbcTemplate jdbcTemplate;

    public ProfileRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final RowMapper<ProfileDto> MAPPER = (rs, rowNum) -> {
        ProfileDto p = new ProfileDto();
        p.setIname(rs.getString("iname"));
        p.setBio(rs.getString("bio"));
        p.setCareer(rs.getString("career"));
        p.setField(rs.getString("field"));
        p.setLink(rs.getString("contact"));               // link = contact 컬럼
        p.setBank(rs.getString("bank"));
        p.setAccount(rs.getString("account"));
        p.setCareerFilename(rs.getString("career_filename"));
        return p;
    };

    /** 본인 강사 정보 조회. instructors 행이 없으면 null. */
    public ProfileDto findByUserId(long userId) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT iname, bio, career, field, contact, bank, account, career_filename " +
                            "FROM instructors WHERE user_id = ?",
                    MAPPER, userId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 텍스트 정보 수정. 수정된 행 수 반환(0이면 대상 없음). 파일은 별도 메서드로. */
    public int update(long userId, ProfileDto p) {
        return jdbcTemplate.update(
                "UPDATE instructors SET iname = ?, bio = ?, career = ?, field = ?, " +
                        "contact = ?, bank = ?, account = ?, updated = SYSTIMESTAMP " +
                        "WHERE user_id = ?",
                p.getIname(), p.getBio(), p.getCareer(), p.getField(),
                p.getLink(), p.getBank(), p.getAccount(), userId);
    }

    /** 경력 파일 교체 시에만 호출. 저장 키(career_file)·원본명(career_filename) 갱신. */
    public void updateCareerFile(long userId, String careerFileKey, String careerFilename) {
        jdbcTemplate.update(
                "UPDATE instructors SET career_file = ?, career_filename = ?, updated = SYSTIMESTAMP " +
                        "WHERE user_id = ?",
                careerFileKey, careerFilename, userId);
    }
}