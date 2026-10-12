package com.example.sesacedu.instructor.feedback;

import com.example.sesacedu.instructor.feedback.dto.AssignmentDetail;
import com.example.sesacedu.instructor.feedback.dto.AssignmentListItem;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 과제 피드백 JDBC.
 *
 * 조인 구조:
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

    // ---------------- 목록 ----------------

    private static final RowMapper<AssignmentListItem> ITEM_MAPPER = (rs, rowNum) ->
            new AssignmentListItem(
                    rs.getInt("aid"),
                    rs.getString("nickname"),
                    rs.getString("lecture_name"),
                    rs.getTimestamp("submitted") == null ? null
                            : rs.getTimestamp("submitted").toLocalDateTime().toLocalDate().toString(),
                    rs.getInt("has_feedback") == 1 ? "done" : "none"
            );

    public List<AssignmentListItem> findAssignments(int instructorUserId, Integer lectureId) {
        StringBuilder sql = new StringBuilder(
                "SELECT a.aid, u.nickname, l.name AS lecture_name, a.submitted, " +
                        "       CASE WHEN a.feedback IS NULL THEN 0 ELSE 1 END AS has_feedback " +
                        "FROM assignments a " +
                        "JOIN lectures l ON a.lid = l.lid " +
                        "JOIN users u ON a.user_id = u.user_id " +
                        "WHERE l.user_id = ? ");
        if (lectureId != null) {
            sql.append("AND a.lid = ? ORDER BY a.submitted DESC");
            return jdbcTemplate.query(sql.toString(), ITEM_MAPPER, instructorUserId, lectureId);
        } else {
            sql.append("ORDER BY a.submitted DESC");
            return jdbcTemplate.query(sql.toString(), ITEM_MAPPER, instructorUserId);
        }
    }

    // ---------------- 상세 ----------------

    private static final RowMapper<AssignmentDetail> DETAIL_MAPPER = (rs, rowNum) -> {
        AssignmentDetail d = new AssignmentDetail();
        d.setAid(rs.getInt("aid"));
        d.setNickname(rs.getString("nickname"));
        d.setLectureName(rs.getString("lecture_name"));
        d.setContent(rs.getString("content"));
        d.setWork(rs.getString("work"));
        d.setWorkPath(rs.getString("work_path"));
        int score = rs.getInt("score");
        d.setScore(rs.wasNull() ? null : score);
        d.setFeedback(rs.getString("feedback"));
        d.setSubmitted(rs.getTimestamp("submitted") == null ? null
                : rs.getTimestamp("submitted").toLocalDateTime().toLocalDate().toString());
        return d;
    };

    /**
     * 과제 상세 1건. 담당 강사(instructorUserId)의 강의에 속한 것만 조회된다.
     * @return 없거나 권한 없으면 null
     */
    public AssignmentDetail findDetail(int aid, int instructorUserId) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT a.aid, u.nickname, l.name AS lecture_name, " +
                            "       a.content, a.work, a.work_path, a.score, a.feedback, a.submitted " +
                            "FROM assignments a " +
                            "JOIN lectures l ON a.lid = l.lid " +
                            "JOIN users u ON a.user_id = u.user_id " +
                            "WHERE a.aid = ? AND l.user_id = ?",
                    DETAIL_MAPPER, aid, instructorUserId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    // ---------------- 다운로드용 파일 정보 ----------------

    /** 다운로드에 필요한 최소 정보: 원본 파일명(work) + 저장 경로(work_path). */
    public static class FileInfo {
        public final String work;      // 원본 파일명
        public final String workPath;  // 저장 경로(키)
        public FileInfo(String work, String workPath) {
            this.work = work;
            this.workPath = workPath;
        }
    }

    private static final RowMapper<FileInfo> FILE_MAPPER = (rs, rowNum) ->
            new FileInfo(rs.getString("work"), rs.getString("work_path"));

    /**
     * 과제의 제출 파일 정보를 조회한다. 담당 강사의 강의에 속한 것만.
     * @return 없거나 권한 없으면 null
     */
    public FileInfo findFileInfo(int aid, int instructorUserId) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT a.work, a.work_path " +
                            "FROM assignments a " +
                            "JOIN lectures l ON a.lid = l.lid " +
                            "WHERE a.aid = ? AND l.user_id = ?",
                    FILE_MAPPER, aid, instructorUserId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    // ---------------- 권한/존재 확인 ----------------

    /**
     * 이 과제(aid)가 해당 강사(instructorUserId)의 담당 강의에 속하는지 확인.
     * @return 담당이면 true, 과제가 없거나 남의 강의면 false
     */
    public boolean isOwnedByInstructor(int aid, int instructorUserId) {
        Integer cnt = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM assignments a " +
                        "JOIN lectures l ON a.lid = l.lid " +
                        "WHERE a.aid = ? AND l.user_id = ?",
                Integer.class, aid, instructorUserId);
        return cnt != null && cnt > 0;
    }

    // ---------------- 저장 / 삭제 ----------------

    /** 피드백/점수 저장(덮어쓰기). 작성/수정 공통. */
    public void saveFeedback(int aid, Integer score, String feedback) {
        jdbcTemplate.update(
                "UPDATE assignments SET score = ?, feedback = ? WHERE aid = ?",
                score, feedback, aid);
    }

    /** 피드백 삭제. 제출물 행은 두고 feedback/score 만 NULL 로 되돌린다. */
    public void clearFeedback(int aid) {
        jdbcTemplate.update(
                "UPDATE assignments SET score = NULL, feedback = NULL WHERE aid = ?",
                aid);
    }
}