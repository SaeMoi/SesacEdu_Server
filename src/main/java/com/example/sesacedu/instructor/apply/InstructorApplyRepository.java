package com.example.sesacedu.instructor.apply;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 강사 신청 기능에서 쓰는 JDBC 접근.
 * 컬럼명은 Oracle 예약어(uid)를 피해 user_id 를 쓴다.
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
     *
     * @return auth 값. 해당 사용자가 없으면 null.
     */
    public Integer findAuthByUserId(int userId) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT auth FROM users WHERE user_id = ?",
                    Integer.class,
                    userId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }
}