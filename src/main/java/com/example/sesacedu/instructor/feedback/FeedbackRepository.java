package com.example.sesacedu.instructor.feedback;

import com.example.sesacedu.instructor.feedback.dto.AssignmentDetail;
import com.example.sesacedu.instructor.feedback.dto.AssignmentListItem;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 과제 피드백 JDBC. (확정 DDL 기준)
 *   assignments a  (제출물)
 *   - JOIN lectures l ON a.lid = l.lid           (강의명, 담당강사 확인)
 *   - JOIN users u    ON a.user_id = u.user_id   (학생 닉네임)
 * 담당 강사 범위: l.user_id = ? (JWT 의 강사)
 */
@Repository
public class FeedbackRepository {

    private final JdbcTemplate jdbcTemplate;

    public FeedbackRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // status 필터는 리터럴 조건 (바인드 인자 없음)
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
                    rs.getString("work_filename"),   // 원본 파일명
                    rs.getTimestamp("submitted") == null ? null
                            : rs.getTimestamp("submitted").toLocalDateTime().toLocalDate().toString(),
                    rs.getInt("has_feedback") == 1 ? "done" : "none"
            );

    public List<AssignmentListItem> findAssignments(long instructorUserId, String status, int offset, int size) {
        StringBuilder sql = new StringBuilder(
                "SELECT a.aid, a.lid, l.name AS lecture_name, u.nickname, a.work_filename, a.submitted, " +
                        "       CASE WHEN a.feedback IS NULL THEN 0 ELSE 1 END AS has_feedback " +
                        "FROM assignments a " +
                        "JOIN lectures l ON a.lid = l.lid " +
                        "JOIN users u    ON a.user_id = u.user_id " +
                        "WHERE l.user_id = ? ");
        appendStatusFilter(sql, status);
        sql.append("ORDER BY a.submitted DESC OFFSET ? ROWS FETCH NEXT ? ROWS ONLY");
        return jdbcTemplate.query(sql.toString(), ITEM_MAPPER, instructorUserId, offset, size);
    }

    public int countAssignments(long instructorUserId, String status) {
        StringBuilder sql = new StringBuilder(
                "SELECT COUNT(*) FROM assignments a " +
                        "JOIN lectures l ON a.lid = l.lid " +
                        "WHERE l.user_id = ? ");
        appendStatusFilter(sql, status);
        Integer cnt = jdbcTemplate.queryForObject(sql.toString(), Integer.class, instructorUserId);
        return cnt == null ? 0 : cnt;
    }

    // ---------------- 상세 ----------------
    private static final RowMapper<AssignmentDetail> DETAIL_MAPPER = (rs, n) -> {
        AssignmentDetail d = new AssignmentDetail();
        d.setAid(rs.getInt("aid"));
        d.setNickname(rs.getString("nickname"));
        d.setLectureName(rs.getString("lecture_name"));
        d.setContent(rs.getString("wnote"));         // 제출 설명 = Wnote
        d.setWork(rs.getString("work_filename"));    // 원본명 = work_filename
        d.setWorkPath(rs.getString("work"));         // 경로   = work
        int score = rs.getInt("score");
        d.setScore(rs.wasNull() ? null : score);
        d.setFeedback(rs.getString("feedback"));
        d.setSubmitted(rs.getTimestamp("submitted") == null ? null
                : rs.getTimestamp("submitted").toLocalDateTime().toLocalDate().toString());
        return d;
    };

    /** 과제 상세 1건. 담당 강사의 강의에 속한 것만. 없거나 권한 없으면 null */
    public AssignmentDetail findDetail(int aid, long instructorUserId) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT a.aid, u.nickname, l.name AS lecture_name, " +
                            "       a.wnote, a.work_filename, a.work, a.score, a.feedback, a.submitted " +
                            "FROM assignments a " +
                            "JOIN lectures l ON a.lid = l.lid " +
                            "JOIN users u    ON a.user_id = u.user_id " +
                            "WHERE a.aid = ? AND l.user_id = ?",
                    DETAIL_MAPPER, aid, instructorUserId);
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

    /** 과제의 제출 파일 정보. 담당 강사의 강의에 속한 것만. 없거나 권한 없으면 null */
    public FileInfo findFileInfo(int aid, long instructorUserId) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT a.work_filename, a.work " +
                            "FROM assignments a JOIN lectures l ON a.lid = l.lid " +
                            "WHERE a.aid = ? AND l.user_id = ?",
                    FILE_MAPPER, aid, instructorUserId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    // ---------------- 권한 확인 ----------------
    /** 이 과제(aid)가 해당 강사의 담당 강의에 속하는지. 없거나 남의 강의면 false */
    public boolean isOwnedByInstructor(int aid, long instructorUserId) {
        Integer cnt = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM assignments a JOIN lectures l ON a.lid = l.lid " +
                        "WHERE a.aid = ? AND l.user_id = ?",
                Integer.class, aid, instructorUserId);
        return cnt != null && cnt > 0;
    }

    // ---------------- 저장 / 삭제 ----------------
    /** 피드백/점수 저장(덮어쓰기). 작성/수정 공통. feedback_at·updated 자동 기록. */
    public void saveFeedback(int aid, Integer score, String feedback) {
        jdbcTemplate.update(
                "UPDATE assignments SET score = ?, feedback = ?, " +
                        "       feedback_at = SYSTIMESTAMP, updated = SYSTIMESTAMP WHERE aid = ?",
                score, feedback, aid);
    }

    /** 피드백 삭제. 제출물 행은 두고 score/feedback/feedback_at 만 비운다. */
    public void clearFeedback(int aid) {
        jdbcTemplate.update(
                "UPDATE assignments SET score = NULL, feedback = NULL, " +
                        "       feedback_at = NULL, updated = SYSTIMESTAMP WHERE aid = ?",
                aid);
    }
}