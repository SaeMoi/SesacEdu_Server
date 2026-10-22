package com.example.sesacedu.instructor.profile;

import com.example.sesacedu.instructor.profile.dto.ProfileDto;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/*
 * ===================== [취약 진단용 - profile] =====================
 *  (V-SQLI/A03) update 의 iname/bio/career/field/contact/bank/account(사용자 입력)를 문자열 연결(저장형 SQLi),
 *               user_id 조건도 바인딩 제거.
 *  (V-XSS/A03)  미살균 저장 → 조회 시 그대로 반환되어 저장형 XSS
 * ================================================================
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

    public ProfileDto findByUserId(long userId) {
        try {
            // [V-SQLI] user_id 바인딩 제거
            return jdbcTemplate.queryForObject(
                    "SELECT iname, bio, career, field, contact, bank, account, career_filename " +
                            "FROM instructors WHERE user_id = " + userId,
                    MAPPER);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** [V-SQLI/V-XSS] 모든 문자열 입력을 작은따옴표로 감싸 연결 → 저장형 SQLi/XSS. */
    public int update(long userId, ProfileDto p) {
        return jdbcTemplate.update(
                "UPDATE instructors SET iname = " + quote(p.getIname()) +
                        ", bio = " + quote(p.getBio()) +
                        ", career = " + quote(p.getCareer()) +
                        ", field = " + quote(p.getField()) +
                        ", contact = " + quote(p.getLink()) +
                        ", bank = " + quote(p.getBank()) +
                        ", account = " + quote(p.getAccount()) +
                        ", updated = SYSTIMESTAMP WHERE user_id = " + userId);
    }

    public void updateCareerFile(long userId, String careerFileKey, String careerFilename) {
        jdbcTemplate.update(
                "UPDATE instructors SET career_file = " + quote(careerFileKey) +
                        ", career_filename = " + quote(careerFilename) +
                        ", updated = SYSTIMESTAMP WHERE user_id = " + userId);
    }

    private static String quote(String v) {
        return v == null ? "NULL" : "'" + v + "'";
    }
}