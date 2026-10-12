package com.example.sesacedu.student.lecture;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

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

    public int countQna(int lid) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM qna WHERE Lid = ?", Integer.class, lid);
        return count == null ? 0 : count;
    }

    /**
     * Q&A 목록.
     *
     * 비밀글도 목록에는 나온다. 다만 제목을 가려서 내보낸다. 그 판단은 Service 가 한다.
     * 여기서 비밀글을 아예 빼면 "몇 번 글이 비었다"는 것으로 존재가 드러나고,
     * 전체 건수도 사람마다 달라져 페이지 계산이 어긋난다.
     */
    public List<QnaRow> findQna(int lid, int offset, int size) {
        String sql = "SELECT q.qid, q.user_id, u.nickname, q.title, q.answered_by, q.answered_at, "
                + "q.isSecret, q.created "
                + "FROM qna q JOIN users u ON u.user_id = q.user_id "
                + "WHERE q.Lid = ? "
                + "ORDER BY q.created DESC NULLS LAST, q.qid DESC "
                + "OFFSET ? ROWS FETCH NEXT ? ROWS ONLY";
        return jdbcTemplate.query(sql, QNA_LIST_MAPPER, lid, offset, size);
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
