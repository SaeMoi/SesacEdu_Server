package com.example.sesacedu.instructor.qna;

import com.example.sesacedu.instructor.qna.dto.QnaDetail;
import com.example.sesacedu.instructor.qna.dto.QnaListItem;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 강사 Q&A JDBC.
 *
 * 조인 구조:
 *   qna q
 *   - JOIN lectures l ON q.lid = l.lid           (강의명, 담당강사 확인)
 *   - JOIN users u    ON q.user_id = u.user_id   (질문자 닉네임)
 * 담당 강사 범위: l.user_id = ? (JWT 의 강사)
 * status: q.answer IS NULL -> 'none', 아니면 'done'
 */
@Repository
public class QnaRepository {

    private final JdbcTemplate jdbcTemplate;

    public QnaRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // ---------------- 목록 ----------------

    private static final RowMapper<QnaListItem> LIST_MAPPER = (rs, rowNum) ->
            new QnaListItem(
                    rs.getInt("qid"),
                    rs.getString("lecture_name"),
                    rs.getString("title"),
                    rs.getInt("has_answer") == 1 ? "done" : "none",
                    rs.getTimestamp("created") == null ? null
                            : rs.getTimestamp("created").toLocalDateTime().toLocalDate().toString()
            );

    /**
     * 강사가 담당한 강의의 Q&A 목록.
     * @param status "none"(미답변)/"done"(답변완료) 필터. null 이면 전체.
     */
    public List<QnaListItem> findList(int instructorUserId, String status) {
        StringBuilder sql = new StringBuilder(
                "SELECT q.qid, l.name AS lecture_name, q.title, q.created, " +
                        "       CASE WHEN q.answer IS NULL THEN 0 ELSE 1 END AS has_answer " +
                        "FROM qna q " +
                        "JOIN lectures l ON q.lid = l.lid " +
                        "WHERE l.user_id = ? ");

        if ("none".equals(status)) {
            sql.append("AND q.answer IS NULL ");
        } else if ("done".equals(status)) {
            sql.append("AND q.answer IS NOT NULL ");
        }
        sql.append("ORDER BY q.created DESC");
        return jdbcTemplate.query(sql.toString(), LIST_MAPPER, instructorUserId);
    }

    // ---------------- 상세 ----------------

    private static final RowMapper<QnaDetail> DETAIL_MAPPER = (rs, rowNum) -> {
        QnaDetail d = new QnaDetail();
        d.setQid(rs.getInt("qid"));
        d.setNickname(rs.getString("nickname"));
        d.setLectureName(rs.getString("lecture_name"));
        d.setTitle(rs.getString("title"));
        d.setQuestion(rs.getString("question"));
        d.setAnswer(rs.getString("answer"));
        d.setCreated(rs.getTimestamp("created") == null ? null
                : rs.getTimestamp("created").toLocalDateTime().toLocalDate().toString());
        return d;
    };

    /** 상세 1건. 담당 강사의 강의에 속한 것만. 없거나 권한 없으면 null. */
    public QnaDetail findDetail(int qid, int instructorUserId) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT q.qid, u.nickname, l.name AS lecture_name, " +
                            "       q.title, q.question, q.answer, q.created " +
                            "FROM qna q " +
                            "JOIN lectures l ON q.lid = l.lid " +
                            "JOIN users u ON q.user_id = u.user_id " +
                            "WHERE q.qid = ? AND l.user_id = ?",
                    DETAIL_MAPPER, qid, instructorUserId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    // ---------------- 권한 확인 ----------------

    /** 이 질문(qid)이 해당 강사의 담당 강의에 속하는지 확인. */
    public boolean isOwnedByInstructor(int qid, int instructorUserId) {
        Integer cnt = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM qna q " +
                        "JOIN lectures l ON q.lid = l.lid " +
                        "WHERE q.qid = ? AND l.user_id = ?",
                Integer.class, qid, instructorUserId);
        return cnt != null && cnt > 0;
    }

    // ---------------- 저장 / 삭제 ----------------

    /** 답변 저장(덮어쓰기). answered_by 에 답변한 강사 user_id 기록. */
    public void saveAnswer(int qid, String answer, int answeredBy) {
        jdbcTemplate.update(
                "UPDATE qna SET answer = ?, answered_by = ? WHERE qid = ?",
                answer, answeredBy, qid);
    }

    /** 답변 삭제. 질문 행은 두고 answer/answered_by 만 NULL 로 되돌린다. */
    public void clearAnswer(int qid) {
        jdbcTemplate.update(
                "UPDATE qna SET answer = NULL, answered_by = NULL WHERE qid = ?",
                qid);
    }
}