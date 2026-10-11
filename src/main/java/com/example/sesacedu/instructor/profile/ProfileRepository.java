package com.example.sesacedu.instructor.profile;

import com.example.sesacedu.instructor.profile.dto.ProfileDto;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 강사 정보 관리 JDBC. instructors 테이블 1행(본인)을 다룬다.
 */
@Repository
public class ProfileRepository {

    private final JdbcTemplate jdbcTemplate;

    public ProfileRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 사용자의 auth 값 조회. 없으면 null. (2=승인된 강사) */
    public Integer findAuthByUserId(int userId) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT auth FROM users WHERE user_id = ?",
                    Integer.class, userId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    private static final RowMapper<ProfileDto> MAPPER = (rs, rowNum) -> {
        ProfileDto p = new ProfileDto();
        p.setIname(rs.getString("iname"));
        p.setBio(rs.getString("bio"));
        p.setCareer(rs.getString("career"));
        p.setLink(rs.getString("link"));
        p.setBank(rs.getString("bank"));
        p.setAccount(rs.getString("account"));
        return p;
    };

    /** 본인 강사 정보 조회. instructors 행이 없으면 null. */
    public ProfileDto findByUserId(int userId) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT iname, bio, career, link, bank, account " +
                            "FROM instructors WHERE user_id = ?",
                    MAPPER, userId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 본인 강사 정보 수정. 수정된 행 수를 반환(0이면 대상 없음). */
    public int update(int userId, ProfileDto p) {
        return jdbcTemplate.update(
                "UPDATE instructors SET iname = ?, bio = ?, career = ?, " +
                        "link = ?, bank = ?, account = ? WHERE user_id = ?",
                p.getIname(), p.getBio(), p.getCareer(),
                p.getLink(), p.getBank(), p.getAccount(), userId);
    }
}