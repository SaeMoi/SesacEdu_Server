package com.example.sesacedu.instructor.qna;

import com.example.sesacedu.instructor.qna.dto.QnaDetail;
import com.example.sesacedu.instructor.qna.dto.QnaListItem;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/*
 * ===================== [취약 진단용 - qna] =====================
 *  (V-AUTHZ/A01) findDetail 의 'AND l.user_id = ?' 삭제 → IDOR(비밀글 isSecret 노출)
 *  (V-SQLI/A03)  saveAnswer 의 answer 문자열 연결(저장형 SQLi), 숫자 조건 바인딩 제거
 *  (V-XSS/A03)   answer 미살균 저장 → 조회 시 그대로 반환되어 저장형 XSS
 * ================================================================
 */
@Repository
public class QnaRepository {

    private final JdbcTemplate jdbcTemplate;

    public QnaRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

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
        // [V-SQLI] 숫자 조건 바인딩 제거
        StringBuilder sql = new StringBuilder(
                "SELECT q.qid, l.name AS lecture_name, q.title, q.created, " +
                        "       CASE WHEN q.answer IS NULL THEN 0 ELSE 1 END AS answered " +
                        "FROM qna q " +
                        "JOIN lectures l ON q.lid = l.lid " +
                        "WHERE l.user_id = " + instructorUserId + " ");
        appendStatusFilter(sql, status);
        sql.append("ORDER BY q.created DESC OFFSET " + offset + " ROWS FETCH NEXT " + size + " ROWS ONLY");
        return jdbcTemplate.query(sql.toString(), ITEM_MAPPER);
    }

    public int countQna(long instructorUserId, String status) {
        StringBuilder sql = new StringBuilder(
                "SELECT COUNT(*) FROM qna q " +
                        "JOIN lectures l ON q.lid = l.lid " +
                        "WHERE l.user_id = " + instructorUserId + " ");
        appendStatusFilter(sql, status);
        Integer cnt = jdbcTemplate.queryForObject(sql.toString(), Integer.class);
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

    /** [V-AUTHZ] 'AND l.user_id = ?' 제거 → 아무 질문이나 조회(IDOR, 비밀글 포함). [V-SQLI] qid 연결. */
    public QnaDetail findDetail(int qid, long instructorUserId) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT q.qid, l.name AS lecture_name, u.nickname, q.title, q.question, q.answer, " +
                            "       q.isSecret AS is_secret, q.created " +
                            "FROM qna q " +
                            "JOIN lectures l ON q.lid = l.lid " +
                            "JOIN users u    ON q.user_id = u.user_id " +
                            "WHERE q.qid = " + qid,
                    DETAIL_MAPPER);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    // ---------------- 권한 확인(미사용) ----------------
    public boolean isOwnedByInstructor(int qid, long instructorUserId) {
        return true;   // [V-AUTHZ] 항상 통과
    }

    // ---------------- 답변 저장 / 삭제 ----------------
    /** [V-SQLI/V-XSS] answer 문자열을 작은따옴표로 감싸 연결 → 저장형 SQLi/XSS. qid/answeredBy 바인딩 제거. */
    public void saveAnswer(int qid, long answeredBy, String answer) {
        String answerSql = (answer == null) ? "NULL" : "'" + answer + "'";
        jdbcTemplate.update(
                "UPDATE qna SET answer = " + answerSql + ", answered_by = " + answeredBy + ", " +
                        "       answered_at = SYSTIMESTAMP, updated = SYSTIMESTAMP WHERE qid = " + qid);
    }

    public void clearAnswer(int qid) {
        jdbcTemplate.update(
                "UPDATE qna SET answer = NULL, answered_by = NULL, " +
                        "       answered_at = NULL, updated = SYSTIMESTAMP WHERE qid = " + qid);
    }
}