package com.example.sesacedu.instructor.apply;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 강사 신청 기능에서 쓰는 JDBC 접근.
 * 컬럼명은 Oracle 예약어(uid)를 피해 user_id 를 쓴다.
 * user_id 는 JWT 에서 온 값이라 long 으로 받는다(바인딩이라 타입만 맞으면 안전).
 */
@Repository
public class InstructorApplyRepository {

    private final JdbcTemplate jdbcTemplate;

    public InstructorApplyRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 사용자의 auth 값을 조회한다.
     * 0 일반 / 1 수강생 / 2 강사 / 3 관리자 / 4 강사 신청 대기
     * @return auth 값. 해당 사용자가 없으면 null.
     */
    public Integer findAuthByUserId(long userId) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT auth FROM users WHERE user_id = ?",
                    Integer.class,
                    userId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 사용자의 실명(uname)을 조회한다. 없으면 null. */
    public String findUnameByUserId(long userId) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT uname FROM users WHERE user_id = ?",
                    String.class, userId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /**
     * instructors 에 신청 행을 추가한다.
     * iname 은 신청 시점엔 실명(uname)으로 채운다. approved_at 은 NULL(대기).
     */
    public void insertApplication(long userId, String iname, String bio, String field,
                                  String careerFile, String fileName) {
        int updated = jdbcTemplate.update(
                "UPDATE instructors SET iname = ?, bio = ?, field = ?, " +
                        "       career_file = ?, career_filename = ?, " +
                        "       applied_at = SYSTIMESTAMP, approved_at = NULL, rejected_at = NULL, " +
                        "       reject_reason = NULL, updated = SYSTIMESTAMP " +
                        "WHERE user_id = ?",
                iname, bio, field, careerFile, fileName, userId);

        if (updated == 0) {   // 기존 신청 행이 없을 때만 새로 넣는다
            jdbcTemplate.update(
                    "INSERT INTO instructors " +
                            "(user_id, iname, bio, field, career_file, career_filename, applied_at, approved_at, rejected_at) " +
                            "VALUES (?, ?, ?, ?, ?, ?, SYSTIMESTAMP, NULL, NULL)",
                    userId, iname, bio, field, careerFile, fileName);
        }
    }

    /** 사용자의 auth 를 바꾼다. (신청 시 4=신청 대기로) */
    public void updateAuth(long userId, int auth) {
        jdbcTemplate.update(
                "UPDATE users SET auth = ? WHERE user_id = ?",
                auth, userId);
    }
}