package com.example.sesacedu.student.lecture;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.util.List;
import java.util.Optional;

import static com.example.sesacedu.student.lecture.LectureClassroomRepository.toLocalDateTime;

/**
 * 강의에 딸린 글(수강평 · Q&A)의 SQL. 목록은 최신순이다.
 */
@Repository
public class LectureBoardRepository {

    /** 수강평 한 줄. */
    private static final RowMapper<ReviewRow> REVIEW_MAPPER = (rs, rowNum) -> new ReviewRow(
            rs.getInt("user_id"),
            rs.getString("nickname"),
            rs.getInt("star"),
            rs.getString("review"),
            toLocalDateTime(rs.getTimestamp("created")));

    /** Q&A 목록 한 줄. 본문과 답변은 SELECT 하지 않아 null 로 둔다. */
    private static final RowMapper<QnaRow> QNA_LIST_MAPPER = (rs, rowNum) -> new QnaRow(
            rs.getInt("qid"),
            rs.getInt("user_id"),
            rs.getString("nickname"),
            rs.getString("title"),
            null,
            null,
            rs.getObject("answered_by") == null ? null : rs.getInt("answered_by"),
            toLocalDateTime(rs.getTimestamp("answered_at")),
            rs.getInt("isSecret") == 1,
            toLocalDateTime(rs.getTimestamp("created")));

    private final JdbcTemplate jdbcTemplate;

    public LectureBoardRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // ───────── 수강평 ─────────

    public int countReviews(int lid) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reviews WHERE Lid = ?", Integer.class, lid);
        return count == null ? 0 : count;
    }

    /** 수강평 목록. 최신순이고 같은 시각이면 user_id 순이다. */
    public List<ReviewRow> findReviews(int lid, int offset, int size) {
        String sql = "SELECT r.user_id, u.nickname, r.star, r.review, r.created "
                + "FROM reviews r JOIN users u ON u.user_id = r.user_id "
                + "WHERE r.Lid = ? "
                + "ORDER BY r.created DESC NULLS LAST, r.user_id DESC "
                + "OFFSET ? ROWS FETCH NEXT ? ROWS ONLY";
        return jdbcTemplate.query(sql, REVIEW_MAPPER, lid, offset, size);
    }

    // ───────── Q&A ─────────

    /** Q&A 건수. 답변 상태(answered · waiting)로 거를 수 있다. */
    public int countQna(int lid, String answeredFilter) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM qna WHERE Lid = ?" + answeredClause(answeredFilter),
                Integer.class, lid);
        return count == null ? 0 : count;
    }

    /** Q&A 목록. 비밀글도 목록에 나오고 제목 가리기는 Service 가 한다. */
    public List<QnaRow> findQna(int lid, int offset, int size, String answeredFilter) {
        String sql = "SELECT q.qid, q.user_id, u.nickname, q.title, q.answered_by, q.answered_at, "
                + "q.isSecret, q.created "
                + "FROM qna q JOIN users u ON u.user_id = q.user_id "
                + "WHERE q.Lid = ?" + answeredClause("q.", answeredFilter) + " "
                + "ORDER BY q.created DESC NULLS LAST, q.qid DESC "
                + "OFFSET ? ROWS FETCH NEXT ? ROWS ONLY";
        return jdbcTemplate.query(sql, QNA_LIST_MAPPER, lid, offset, size);
    }

    private String answeredClause(String filter) {
        return answeredClause("", filter);
    }

    /** 답변 상태 조건절. 답변 본문이나 답변자가 있으면 답변 완료다. */
    private String answeredClause(String prefix, String filter) {
        if ("answered".equals(filter)) {
            return " AND (" + prefix + "answer IS NOT NULL OR " + prefix + "answered_by IS NOT NULL)";
        }
        if ("waiting".equals(filter)) {
            return " AND " + prefix + "answer IS NULL AND " + prefix + "answered_by IS NULL";
        }
        return "";
    }

    /** Q&A 한 건. 본문과 답변까지 가져온다. */
    public Optional<QnaRow> findQnaDetail(int lid, int qid) {
        String sql = "SELECT q.qid, q.user_id, u.nickname, q.title, q.question, q.answer, "
                + "q.answered_by, q.answered_at, q.isSecret, q.created "
                + "FROM qna q JOIN users u ON u.user_id = q.user_id "
                + "WHERE q.qid = ?";
        try {
            return Optional.of(jdbcTemplate.queryForObject(sql, (rs, rowNum) -> new QnaRow(
                    rs.getInt("qid"),
                    rs.getInt("user_id"),
                    rs.getString("nickname"),
                    rs.getString("title"),
                    rs.getString("question"),
                    rs.getString("answer"),
                    rs.getObject("answered_by") == null ? null : rs.getInt("answered_by"),
                    toLocalDateTime(rs.getTimestamp("answered_at")),
                    rs.getInt("isSecret") == 1,
                    toLocalDateTime(rs.getTimestamp("created"))), qid));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    /** 이 강의에 이미 수강평을 썼는지. 1인 1건이다. */
    public boolean existsReview(int lid, int userId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reviews WHERE Lid = ? AND user_id = ?", Integer.class, lid, userId);
        return n != null && n > 0;
    }

    /** 수강평을 넣는다. */
    public void insertReview(int lid, int userId, int star, String review) {
        jdbcTemplate.update(
                "INSERT INTO reviews (Lid, user_id, star, review, created) "
                        + "VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)",
                lid, userId, star, review);
    }

    /** 수강평을 고친다. 처음 쓴 시각(created)은 그대로 두고 updated 를 남긴다. */
    public int updateReview(int lid, int userId, int star, String review) {
        return jdbcTemplate.update(
                "UPDATE reviews SET star = ?, review = ?, updated = CURRENT_TIMESTAMP "
                        + "WHERE Lid = ? AND user_id = ?",
                star, review, lid, userId);
    }

    /** 수강평을 지운다. */
    public int deleteReview(int lid, int userId) {
        return jdbcTemplate.update(
                "DELETE FROM reviews WHERE Lid = ? AND user_id = ?", lid, userId);
    }

    /**
     * 질문을 넣는다. 만들어진 qid 를 돌려준다.
     *
     * isSecret 은 DB 에 0/1 로 들어간다. Oracle 에 boolean 타입이 없다.
     */
    public int insertQna(int lid, int userId, String title, String question, boolean secret) {
        KeyHolder key = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO qna (Lid, user_id, title, question, isSecret, created) "
                            + "VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                    new String[]{"QID"});
            ps.setInt(1, lid);
            ps.setInt(2, userId);
            ps.setString(3, title);
            ps.setString(4, question);
            ps.setInt(5, secret ? 1 : 0);
            return ps;
        }, key);
        Number generated = key.getKey();
        return generated == null ? 0 : generated.intValue();
    }

    /** 이 강의의 담당 강사 user_id. */
    public Optional<Integer> findInstructorUserId(int lid) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(
                    "SELECT user_id FROM lectures WHERE Lid = ?", Integer.class, lid));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }
}
