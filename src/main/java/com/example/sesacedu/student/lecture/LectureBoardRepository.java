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
 * 강의에 딸린 글(수강평 · Q&A)의 SQL.
 *
 * 값은 전부 ? 로 바인딩한다. 페이지 자르기(OFFSET/FETCH)의 숫자도 값이라 바인딩된다.
 * 정렬은 화면에서 고르는 값이 아니라 최신순으로 고정이므로 문자열을 받지 않는다.
 * 받을 일이 없으면 그 자리는 인젝션 통로가 되지 않는다.
 */
@Repository
public class LectureBoardRepository {

    /** 수강평 한 줄. 작성자 표시는 닉네임만 쓴다. */
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

    /**
     * 수강평 목록. 최신순 고정이다.
     *
     * created 가 NULL 인 옛 데이터가 뒤로 가도록 NULLS LAST 를 붙이고, 같은 시각이면
     * user_id 로 순서를 고정한다. 정렬 기준이 흔들리면 페이지를 넘길 때 같은 글이
     * 두 번 보이거나 아예 건너뛰어진다.
     */
    public List<ReviewRow> findReviews(int lid, int offset, int size) {
        String sql = "SELECT r.user_id, u.nickname, r.star, r.review, r.created "
                + "FROM reviews r JOIN users u ON u.user_id = r.user_id "
                + "WHERE r.Lid = ? "
                + "ORDER BY r.created DESC NULLS LAST, r.user_id DESC "
                + "OFFSET ? ROWS FETCH NEXT ? ROWS ONLY";
        return jdbcTemplate.query(sql, REVIEW_MAPPER, lid, offset, size);
    }

    // ───────── Q&A ─────────

    /**
     * Q&A 건수. 답변 상태 필터를 함께 받는다.
     *
     * 목록과 같은 조건을 써야 한다. 한쪽에만 필터를 걸면 "전체 32건"이라고
     * 적힌 화면에 미답변 3건만 나오고 페이지 번호가 4개까지 생긴다.
     */
    public int countQna(int lid, String answeredFilter) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM qna WHERE Lid = ?" + answeredClause(answeredFilter),
                Integer.class, lid);
        return count == null ? 0 : count;
    }

    /**
     * Q&A 목록.
     *
     * 비밀글도 목록에는 나온다. 다만 제목을 가려서 내보낸다. 그 판단은 Service 가 한다.
     * 여기서 비밀글을 아예 빼면 "몇 번 글이 비었다"는 것으로 존재가 드러나고,
     * 전체 건수도 사람마다 달라져 페이지 계산이 어긋난다.
     */
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

    /**
     * 답변 상태 조건절을 만든다.
     *
     * 값을 SQL 에 이어 붙이지 않는다. 받은 문자열을 두 개의 정해진 값과 맞춰 보고
     * 미리 쓴 조건절 중 하나를 고르는 방식이다. 바인딩 파라미터로 둘 수 없는
     * 자리(컬럼·연산자)라 문자열을 그대로 넣으면 그게 SQL 주입 자리가 된다.
     *
     * 아는 값이 아니면 조건을 걸지 않는다. Service 가 이미 거르지만, 저장소가
     * 혼자 호출돼도 안전하도록 여기서도 닫아 둔다.
     */
    private String answeredClause(String prefix, String filter) {
        // 목록의 answered 표시(답변 본문 또는 답변자가 있음)와 같은 기준을 쓴다.
        // 기준이 다르면 "답변완료"로 거른 목록에 answered=false 인 글이 섞인다.
        if ("answered".equals(filter)) {
            return " AND (" + prefix + "answer IS NOT NULL OR " + prefix + "answered_by IS NOT NULL)";
        }
        if ("waiting".equals(filter)) {
            return " AND " + prefix + "answer IS NULL AND " + prefix + "answered_by IS NULL";
        }
        return "";
    }

    /**
     * Q&A 한 건. 본문과 답변까지 가져온다.
     *
     * Lid 와 qid 를 둘 다 조건에 넣는다. qid 만으로 찾으면 다른 강의의 질문 번호를
     * 넣었을 때 그대로 열린다. 주소에 강의 번호가 있는데 쓰지 않으면 그게 IDOR 이다.
     */
    public Optional<QnaRow> findQnaDetail(int lid, int qid) {
        String sql = "SELECT q.qid, q.user_id, u.nickname, q.title, q.question, q.answer, "
                + "q.answered_by, q.answered_at, q.isSecret, q.created "
                + "FROM qna q JOIN users u ON u.user_id = q.user_id "
                + "WHERE q.Lid = ? AND q.qid = ?";
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
                    toLocalDateTime(rs.getTimestamp("created"))), lid, qid));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    /** 내가 이 강의에 이미 수강평을 썼는지. reviews 는 (Lid, user_id) 가 PK 라 1인 1건이다. */
    public boolean existsReview(int lid, int userId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reviews WHERE Lid = ? AND user_id = ?", Integer.class, lid, userId);
        return n != null && n > 0;
    }

    /** 수강평을 넣는다. 번호가 따로 없는 테이블이라 돌려줄 키가 없다. */
    public void insertReview(int lid, int userId, int star, String review) {
        jdbcTemplate.update(
                "INSERT INTO reviews (Lid, user_id, star, review, created) "
                        + "VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)",
                lid, userId, star, review);
    }

    /**
     * 내 수강평을 고친다. 고친 행 수를 돌려준다.
     *
     * 조건절에 user_id 를 넣는다. reviews 는 (Lid, user_id) 가 PK 라 Lid 만으로는
     * 그 강의의 모든 리뷰가 대상이 된다. 조회한 뒤 코드에서 작성자를 비교하는
     * 방식을 쓰지 않는 이유는 과제 쪽과 같다 — 분기를 하나 빠뜨리면 그대로 뚫린다.
     *
     * created 는 건드리지 않는다. 처음 쓴 시각이 수정으로 바뀌면 "언제 쓴 글인가"를
     * 알 수 없게 된다. 대신 updated 를 남긴다.
     */
    public int updateReview(int lid, int userId, int star, String review) {
        return jdbcTemplate.update(
                "UPDATE reviews SET star = ?, review = ?, updated = CURRENT_TIMESTAMP "
                        + "WHERE Lid = ? AND user_id = ?",
                star, review, lid, userId);
    }

    /** 내 수강평을 지운다. 지운 행 수를 돌려준다. 여기도 user_id 가 조건절에 있다. */
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

    /** 이 강의의 담당 강사 user_id. 비밀글 열람 권한을 판단할 때 쓴다. */
    public Optional<Integer> findInstructorUserId(int lid) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(
                    "SELECT user_id FROM lectures WHERE Lid = ?", Integer.class, lid));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }
}
