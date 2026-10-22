package com.example.sesacedu.instructor.feedback;

import com.example.sesacedu.instructor.feedback.dto.AssignmentDetail;
import com.example.sesacedu.instructor.feedback.dto.AssignmentListItem;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/*
 * ===================== [취약 진단용 - feedback] =====================
 *  (V-AUTHZ/A01) findDetail/findFileInfo 의 'AND l.user_id = ?' 삭제 → IDOR
 *  (V-SQLI/A03)  saveFeedback 의 feedback 문자열 연결(저장형 SQLi), 숫자 조건 바인딩 제거
 *  (V-XSS/A03)   feedback 등 미살균 저장 → 조회 시 그대로 반환되어 저장형 XSS
 * ================================================================
 */
@Repository
public class FeedbackRepository {

    private final JdbcTemplate jdbcTemplate;

    public FeedbackRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static void appendStatusFilter(StringBuilder sql, String status) {
        if ("none".equals(status))      sql.append("AND a.feedback IS NULL ");
        else if ("done".equals(status)) sql.append("AND a.feedback IS NOT NULL ");
    }

    // ---------------- 목록 ----------------
    private static final RowMapper<AssignmentListItem> ITEM_MAPPER = (rs, n) ->
            new AssignmentListItem(
                    rs.getInt("aid"),
                    rs.getInt("lid"),
                    rs.getString("nickname"),
                    rs.getString("lecture_name"),
                    rs.getString("work_filename"),
                    rs.getTimestamp("submitted") == null ? null
                            : rs.getTimestamp("submitted").toLocalDateTime().toLocalDate().toString(),
                    rs.getInt("has_feedback") == 1 ? "done" : "none"
            );

    public List<AssignmentListItem> findAssignments(long instructorUserId, String status, int offset, int size) {
        // [V-SQLI] 숫자 조건 바인딩 제거
        StringBuilder sql = new StringBuilder(
                "SELECT a.aid, a.lid, l.name AS lecture_name, u.nickname, a.work_filename, a.submitted, " +
                        "       CASE WHEN a.feedback IS NULL THEN 0 ELSE 1 END AS has_feedback " +
                        "FROM assignments a " +
                        "JOIN lectures l ON a.lid = l.lid " +
                        "JOIN users u    ON a.user_id = u.user_id " +
                        "WHERE l.user_id = " + instructorUserId + " ");
        appendStatusFilter(sql, status);
        sql.append("ORDER BY a.submitted DESC OFFSET " + offset + " ROWS FETCH NEXT " + size + " ROWS ONLY");
        return jdbcTemplate.query(sql.toString(), ITEM_MAPPER);
    }

    public int countAssignments(long instructorUserId, String status) {
        StringBuilder sql = new StringBuilder(
                "SELECT COUNT(*) FROM assignments a " +
                        "JOIN lectures l ON a.lid = l.lid " +
                        "WHERE l.user_id = " + instructorUserId + " ");
        appendStatusFilter(sql, status);
        Integer cnt = jdbcTemplate.queryForObject(sql.toString(), Integer.class);
        return cnt == null ? 0 : cnt;
    }

    // ---------------- 상세 ----------------
    private static final RowMapper<AssignmentDetail> DETAIL_MAPPER = (rs, n) -> {
        AssignmentDetail d = new AssignmentDetail();
        d.setAid(rs.getInt("aid"));
        d.setNickname(rs.getString("nickname"));
        d.setLectureName(rs.getString("lecture_name"));
        d.setContent(rs.getString("wnote"));
        d.setWork(rs.getString("work_filename"));
        d.setWorkPath(rs.getString("work"));
        int score = rs.getInt("score");
        d.setScore(rs.wasNull() ? null : score);
        d.setFeedback(rs.getString("feedback"));
        d.setSubmitted(rs.getTimestamp("submitted") == null ? null
                : rs.getTimestamp("submitted").toLocalDateTime().toLocalDate().toString());
        return d;
    };

    /** [V-AUTHZ] 'AND l.user_id = ?' 제거 → 아무 과제나 조회(IDOR). instructorUserId 는 무시. */
    public AssignmentDetail findDetail(int aid, long instructorUserId) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT a.aid, u.nickname, l.name AS lecture_name, " +
                            "       a.wnote, a.work_filename, a.work, a.score, a.feedback, a.submitted " +
                            "FROM assignments a " +
                            "JOIN lectures l ON a.lid = l.lid " +
                            "JOIN users u    ON a.user_id = u.user_id " +
                            "WHERE a.aid = " + aid,
                    DETAIL_MAPPER);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    // ---------------- 다운로드용 파일 정보 ----------------
    public static class FileInfo {
        public final String work;      // 원본 파일명 (work_filename)
        public final String workPath;  // 저장 경로   (work)
        public FileInfo(String work, String workPath) {
            this.work = work;
            this.workPath = workPath;
        }
    }

    private static final RowMapper<FileInfo> FILE_MAPPER = (rs, n) ->
            new FileInfo(rs.getString("work_filename"), rs.getString("work"));

    /** [V-AUTHZ] 담당강의 조건 제거 → 아무 과제 파일이나 조회(IDOR). */
    public FileInfo findFileInfo(int aid, long instructorUserId) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT a.work_filename, a.work " +
                            "FROM assignments a JOIN lectures l ON a.lid = l.lid " +
                            "WHERE a.aid = " + aid,
                    FILE_MAPPER);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    // ---------------- 권한 확인(미사용) ----------------
    public boolean isOwnedByInstructor(int aid, long instructorUserId) {
        return true;   // [V-AUTHZ] 항상 통과
    }

    // ---------------- 저장 / 삭제 ----------------
    /** [V-SQLI/V-XSS] feedback 문자열을 작은따옴표로 감싸 연결 → 저장형 SQLi, 미살균 저장 → XSS. */
    public void saveFeedback(int aid, Integer score, String feedback) {
        String scoreSql = (score == null) ? "NULL" : String.valueOf(score);
        jdbcTemplate.update(
                "UPDATE assignments SET score = " + scoreSql + ", feedback = '" + feedback + "', " +
                        "       feedback_at = SYSTIMESTAMP, updated = SYSTIMESTAMP WHERE aid = " + aid);
    }

    public void clearFeedback(int aid) {
        jdbcTemplate.update(
                "UPDATE assignments SET score = NULL, feedback = NULL, " +
                        "       feedback_at = NULL, updated = SYSTIMESTAMP WHERE aid = " + aid);
    }
}