package com.example.sesacedu.instructor.apply;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/*
 * ===================== [취약 진단용 - apply] =====================
 *  (V-SQLI/A03) 바인딩(?) 제거 → 문자열 연결.
 *     insertApplication 의 iname/bio(=소개)/field(=분야)/career_filename 이 UPDATE·INSERT 문에 그대로 삽입(저장형 SQLi).
 *     저장된 값은 응답에서 미살균으로 반환 → 저장형 XSS(A03) 로도 이어짐.
 * ================================================================
 */
@Repository
public class InstructorApplyRepository {

    private final JdbcTemplate jdbcTemplate;

    public InstructorApplyRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Integer findAuthByUserId(long userId) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT auth FROM users WHERE user_id = " + userId, Integer.class);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    public String findUnameByUserId(long userId) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT uname FROM users WHERE user_id = " + userId, String.class);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /**
     * [V-SQLI] 재신청이면 UPDATE, 없으면 INSERT (upsert). 흐름은 원본과 동일하되
     * 사용자 입력 문자열을 작은따옴표로 감싸 연결한다 → 저장형 SQL Injection.
     */
    public void insertApplication(long userId, String iname, String bio, String field,
                                  String careerFile, String fileName) {
        int updated = jdbcTemplate.update(
                "UPDATE instructors SET iname = '" + iname + "', bio = '" + bio + "', field = '" + field + "', " +
                        "       career_file = '" + careerFile + "', career_filename = '" + fileName + "', " +
                        "       applied_at = SYSTIMESTAMP, approved_at = NULL, rejected_at = NULL, " +
                        "       reject_reason = NULL, updated = SYSTIMESTAMP " +
                        "WHERE user_id = " + userId);

        if (updated == 0) {
            jdbcTemplate.update(
                    "INSERT INTO instructors " +
                            "(user_id, iname, bio, field, career_file, career_filename, applied_at, approved_at, rejected_at) " +
                            "VALUES (" + userId + ", '" + iname + "', '" + bio + "', '" + field + "', '" +
                            careerFile + "', '" + fileName + "', SYSTIMESTAMP, NULL, NULL)");
        }
    }

    public void updateAuth(long userId, int auth) {
        jdbcTemplate.update("UPDATE users SET auth = " + auth + " WHERE user_id = " + userId);
    }
}