package com.example.sesacedu.student.lecture;

import com.example.sesacedu.student.lecture.EnrollmentProgress;
import com.example.sesacedu.student.lecture.LectureCard;
import com.example.sesacedu.student.lecture.LectureDetail;
import com.example.sesacedu.student.lecture.OtherLecture;
import com.example.sesacedu.student.lecture.ReviewSummary;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 강의 조회의 SQL. JdbcTemplate 으로 SQL 을 직접 쓴다.
 */
@Repository("studentLectureRepository")
public class LectureRepository {

    // 조회 결과 한 줄(ResultSet)을 LectureCard 객체 하나로 바꾸는 규칙.
    // 컬럼을 이름으로 꺼내므로 SELECT 의 AS 별칭과 여기 문자열이 맞아야 한다.
    private static final RowMapper<LectureCard> CARD_MAPPER = (rs, rowNum) -> new LectureCard(
            rs.getInt("lid"),
            rs.getString("name"),
            rs.getString("headline"),
            rs.getString("picture"),
            rs.getString("instructor"),
            rs.getString("category"),
            rs.getString("level_value"),
            rs.getInt("price"),
            // getDouble 은 값이 NULL 일 때 0.0 을 돌려준다. 리뷰가 없는 것과 평점 0점이
            // 구분되지 않으므로 getObject 로 NULL 여부를 먼저 본다.
            rs.getObject("rating") == null ? null : rs.getDouble("rating"),
            rs.getInt("review_count")
    );

    private final JdbcTemplate jdbcTemplate;

    public LectureRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // 조건에 맞는 공개 강의가 몇 건인지만 센다. 페이지 버튼 개수를 계산하는 데 쓴다.
    public int countOpenLectures(String keyword, String category, String level) {
        StringBuilder sql = new StringBuilder(
                "SELECT COUNT(*) FROM lectures l "
                        + "LEFT JOIN instructors i ON i.user_id = l.user_id "
                        + "WHERE l.status = 'open'");
        List<Object> params = new ArrayList<>();
        appendFilters(sql, params, keyword, category, level);
        Integer count = jdbcTemplate.queryForObject(sql.toString(), Integer.class, params.toArray());
        return count == null ? 0 : count;
    }

    public List<LectureCard> findOpenLectures(String keyword, String category, String level,
                                              String orderBy, int offset, int size) {
        StringBuilder sql = new StringBuilder(
                "SELECT l.Lid AS lid, l.name, l.headline, l.picture, i.iname AS instructor, "
                        // 난이도 컬럼은 Llevel 이다. level 로 두면 Oracle 이 계층 질의
                        // 가상컬럼으로 해석해 ORA-01788 이 난다. Lid·Lprice 와 같은 L 접두 규칙이다.
                        + "l.category, l.Llevel AS level_value, l.Lprice AS price, "
                        // 평점은 reviews 를 미리 묶어 둔 결과와 LEFT JOIN 한다. 리뷰가 없는
                        // 강의도 목록에서 빠지지 않아야 하므로 INNER JOIN 을 쓰지 않는다.
                        + "r.rating, NVL(r.review_count, 0) AS review_count "
                        + "FROM lectures l "
                        + "LEFT JOIN instructors i ON i.user_id = l.user_id "
                        + "LEFT JOIN (SELECT Lid, ROUND(AVG(star), 1) AS rating, COUNT(*) AS review_count "
                        + "FROM reviews GROUP BY Lid) r ON r.Lid = l.Lid "
                        + "WHERE l.status = 'open'");
        List<Object> params = new ArrayList<>();
        appendFilters(sql, params, keyword, category, level);
        sql.append(" ORDER BY ").append(orderBy);
        // Oracle 12c 이상의 페이지 자르기 구문.
        sql.append(" OFFSET ? ROWS FETCH NEXT ? ROWS ONLY");
        params.add(offset);
        params.add(size);
        return jdbcTemplate.query(sql.toString(), CARD_MAPPER, params.toArray());
    }

    public Optional<LectureDetail> findOpenById(int lid) {
        String sql = "SELECT l.Lid AS lid, l.user_id AS instructor_user_id, l.name, l.headline, l.summary, "
                + "l.category, l.Llevel AS level_value, l.picture, l.preview, l.Lprice AS price, "
                + "i.iname, i.bio, i.field, i.career "
                + "FROM lectures l "
                + "LEFT JOIN instructors i ON i.user_id = l.user_id "
                // 공개(open) 강의만 상세를 보여 준다.
                + "WHERE l.Lid = ? AND l.status = 'open'";
        try {
            return Optional.of(jdbcTemplate.queryForObject(sql, this::mapDetail, lid));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    /**
     * 이 사용자가 이 강의를 수강 중인지 본다. 수강 중이면 진도(done, position)를 준다.
     */
    public Optional<EnrollmentProgress> findEnrollment(int userId, int lid) {
        String sql = "SELECT done, position FROM enrollments WHERE user_id = ? AND Lid = ?";
        try {
            return Optional.of(jdbcTemplate.queryForObject(sql, (rs, rowNum) -> new EnrollmentProgress(
                    // Oracle 에 boolean 타입이 없어 done 을 숫자로 저장한다. 1 이면 수료다.
                    rs.getInt("done") == 1,
                    rs.getObject("position") == null ? null : rs.getInt("position")
            ), userId, lid));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    public ReviewSummary findReviewSummary(int lid) {
        String sql = "SELECT AVG(star) AS rating, COUNT(*) AS review_count FROM reviews WHERE Lid = ?";
        return jdbcTemplate.queryForObject(sql, (rs, rowNum) -> {
            int count = rs.getInt("review_count");
            Double rating = rs.getObject("rating") == null ? null : rs.getDouble("rating");
            // 소수 첫째 자리까지만 쓴다. 10 을 곱해 반올림한 뒤 다시 나눈다.
            if (rating != null) {
                rating = Math.round(rating * 10.0) / 10.0;
            }
            return new ReviewSummary(count == 0 ? null : rating, count);
        }, lid);
    }

    public List<OtherLecture> findOtherOpenLectures(int instructorUserId, int excludeLid) {
        String sql = "SELECT Lid AS lid, name, Lprice AS price FROM lectures "
                // 같은 강사의 다른 공개 강의. 보고 있는 강의 자신은 Lid <> ? 로 뺀다.
                + "WHERE user_id = ? AND Lid <> ? AND status = 'open' "
                + "ORDER BY created DESC NULLS LAST, Lid DESC "
                + "FETCH FIRST 5 ROWS ONLY";
        return jdbcTemplate.query(sql, (rs, rowNum) -> new OtherLecture(
                rs.getInt("lid"),
                rs.getString("name"),
                rs.getInt("price")
        ), instructorUserId, excludeLid);
    }

    private LectureDetail mapDetail(ResultSet rs, int rowNum) throws SQLException {
        return new LectureDetail(
                rs.getInt("lid"),
                rs.getInt("instructor_user_id"),
                rs.getString("name"),
                rs.getString("headline"),
                rs.getString("summary"),
                rs.getString("category"),
                rs.getString("level_value"),
                rs.getString("picture"),
                rs.getString("preview"),
                rs.getInt("price"),
                rs.getString("iname"),
                rs.getString("bio"),
                rs.getString("field"),
                rs.getString("career")
        );
    }

    private void appendFilters(StringBuilder sql, List<Object> params,
                               String keyword, String category, String level) {
        // 보낸 조건만 WHERE 에 덧붙인다.
        if (keyword != null) {
            sql.append(" AND l.name LIKE '%").append(keyword).append("%'");
        }
        if (category != null) {
            sql.append(" AND l.category = '").append(category).append("'");
        }
        if (level != null) {
            sql.append(" AND l.Llevel = '").append(level).append("'");
        }
    }
}
