package com.example.sesacedu.instructor.feedback;

import com.example.sesacedu.instructor.feedback.dto.AssignmentListItem;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 과제 피드백 목록 조회 JDBC.
 *
 * 조인 구조:
 *   assignments a  (제출물)
 *   - JOIN lectures l ON a.lid = l.lid           (강의명, 담당강사 확인)
 *   - JOIN users u    ON a.user_id = u.user_id   (학생 닉네임)
 * 담당 강사 범위: l.user_id = ? (JWT 의 강사)
 * feedbackStatus: a.feedback IS NULL -> 'none', 아니면 'done'
 */
@Repository
public class FeedbackRepository {

    private final JdbcTemplate jdbcTemplate;

    public FeedbackRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final RowMapper<AssignmentListItem> ITEM_MAPPER = (rs, rowNum) ->
            new AssignmentListItem(
                    rs.getInt("aid"),
                    rs.getString("nickname"),
                    rs.getString("lecture_name"),
                    // 제출일을 YYYY-MM-DD 로. null 가능성 처리.
                    rs.getTimestamp("submitted") == null ? null
                            : rs.getTimestamp("submitted").toLocalDateTime().toLocalDate().toString(),
                    // feedback 은 NULL 여부만 필요하므로 플래그로만 조회 (CLOB 전체를 읽지 않음)
                    rs.getInt("has_feedback") == 1 ? "done" : "none"
            );

    /**
     * 강사(instructorUserId)가 담당한 강의의 과제 목록.
     * lectureId 가 주어지면 그 강의만, null 이면 담당 전체 강의.
     */
    public List<AssignmentListItem> findAssignments(int instructorUserId, Integer lectureId) {
        StringBuilder sql = new StringBuilder(
                "SELECT a.aid, u.nickname, l.name AS lecture_name, a.submitted, " +
                        "       CASE WHEN a.feedback IS NULL THEN 0 ELSE 1 END AS has_feedback " +
                        "FROM assignments a " +
                        "JOIN lectures l ON a.lid = l.lid " +
                        "JOIN users u ON a.user_id = u.user_id " +
                        "WHERE l.user_id = ? ");

        if (lectureId != null) {
            sql.append("AND a.lid = ? ");
            sql.append("ORDER BY a.submitted DESC");
            return jdbcTemplate.query(sql.toString(), ITEM_MAPPER, instructorUserId, lectureId);
        } else {
            sql.append("ORDER BY a.submitted DESC");
            return jdbcTemplate.query(sql.toString(), ITEM_MAPPER, instructorUserId);
        }
    }
}