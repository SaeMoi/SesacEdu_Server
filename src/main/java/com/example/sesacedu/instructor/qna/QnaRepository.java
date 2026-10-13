package com.example.sesacedu.instructor.qna;

import com.example.sesacedu.instructor.qna.dto.QnaDetail;
import com.example.sesacedu.instructor.qna.dto.QnaListItem;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 강사 Q&A JDBC. (확정 DDL 기준)
 *   qna q (강의 문의)
 *   - JOIN lectures l ON q.lid = l.lid           (강의명, 담당강사 확인)
 *   - JOIN users u    ON q.user_id = u.user_id   (질문자 닉네임)
 * 담당 강사 범위: l.user_id = ? (JWT 의 강사). 답변완료 여부: q.answer NULL 여부.
 */
@Repository
public class QnaRepository {

    private final JdbcTemplate jdbcTemplate;

    public QnaRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // status 필터는 리터럴 조건 (바인드 인자 없음). none = 미답변, done = 답변완료
    private static void appendStatusFilter(StringBuilder sql, String status) {
        if ("none".equals(status))      sql.append("AND q.answer IS NULL ");
        else if ("done".equals(status)) sql.append("AND q.answer IS NOT NULL ");
    }

    // ---------------- 목록 ----------------
    private static final RowMapper<QnaListItem> ITEM_MAPPER = (rs, n) ->
            new QnaListItem(
                    rs.getInt("qid"),
                    rs.getString("lecture_name"),
                    rs.getString("title"),
                    rs.getInt("answered") == 1 ? "done" : "none",
                    rs.getTimestamp("created") == null ? null
                            : rs.getTimestamp("created").toLocalDateTime().toLocalDate().toString()
            );

    public List<QnaListItem> findQna(long instructorUserId, String status, int offset, int size) {
        StringBuilder sql = new StringBuilder(
                "SELECT q.qid, l.name AS lecture_name, q.title, q.created, " +
                        "       CASE WHEN q.answer IS NULL THEN 0 ELSE 1 END AS answered " +
                        "FROM qna q " +
                        "JOIN lectures l ON q.lid = l.lid " +
                        "WHERE l.user_id = ? ");
        appendStatusFilter(sql, status);
        sql.append("ORDER BY q.created DESC OFFSET ? ROWS FETCH NEXT ? ROWS ONLY");
        return jdbcTemplate.query(sql.toString(), ITEM_MAPPER, instructorUserId, offset, size);
    }

    public int countQna(long instructorUserId, String status) {
        StringBuilder sql = new StringBuilder(
                "SELECT COUNT(*) FROM qna q " +
                        "JOIN lectures l ON q.lid = l.lid " +
                        "WHERE l.user_id = ? ");
        appendStatusFilter(sql, status);
        Integer cnt = jdbcTemplate.queryForObject(sql.toString(), Integer.class, instructorUserId);
        return cnt == null ? 0 : cnt;
    }

    // ---------------- 상세 ----------------
    private static final RowMapper<QnaDetail> DETAIL_MAPPER = (rs, n) -> {
        QnaDetail d = new QnaDetail();
        d.setQid(rs.getInt("qid"));
        d.setLectureName(rs.getString("lecture_name"));
        d.setNickname(rs.getString("nickname"));
        d.setTitle(rs.getString("title"));
        d.setQuestion(rs.getString("question"));
        d.setAnswer(rs.getString("answer"));
        d.setSecret(rs.getInt("is_secret") == 1);
        d.setStatus(rs.getString("answer") == null ? "none" : "done");
        d.setCreated(rs.getTimestamp("created") == null ? null
                : rs.getTimestamp("created").toLocalDateTime().toLocalDate().toString());
        return d;
    };

    /** 상세 1건. 담당 강사의 강의에 속한 것만. 없거나 권한 없으면 null */
    public QnaDetail findDetail(int qid, long instructorUserId) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT q.qid, l.name AS lecture_name, u.nickname, q.title, q.question, q.answer, " +
                            "       q.isSecret AS is_secret, q.created " +
                            "FROM qna q " +
                            "JOIN lectures l ON q.lid = l.lid " +
                            "JOIN users u    ON q.user_id = u.user_id " +
                            "WHERE q.qid = ? AND l.user_id = ?",
                    DETAIL_MAPPER, qid, instructorUserId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    // ---------------- 권한 확인 ----------------
    /** 이 질문(qid)이 해당 강사의 담당 강의에 속하는지. 없거나 남의 강의면 false */
    public boolean isOwnedByInstructor(int qid, long instructorUserId) {
        Integer cnt = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM qna q JOIN lectures l ON q.lid = l.lid " +
                        "WHERE q.qid = ? AND l.user_id = ?",
                Integer.class, qid, instructorUserId);
        return cnt != null && cnt > 0;
    }

    // ---------------- 답변 저장 / 삭제 ----------------
    /** 답변 저장(작성·수정 공통). answered_by = 답변 강사, answered_at·updated 자동 기록. */
    public void saveAnswer(int qid, long answeredBy, String answer) {
        jdbcTemplate.update(
                "UPDATE qna SET answer = ?, answered_by = ?, " +
                        "       answered_at = SYSTIMESTAMP, updated = SYSTIMESTAMP WHERE qid = ?",
                answer, answeredBy, qid);
    }

    /** 답변 삭제. 질문 행은 두고 answer/answered_by/answered_at 만 비운다. */
    public void clearAnswer(int qid) {
        jdbcTemplate.update(
                "UPDATE qna SET answer = NULL, answered_by = NULL, " +
                        "       answered_at = NULL, updated = SYSTIMESTAMP WHERE qid = ?",
                qid);
    }
}