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
 * 강의 조회의 SQL 을 모아 둔 곳. DB 와 직접 대화하는 유일한 계층이다.
 *
 * JPA 가 아니라 JDBC 를 쓴다. JdbcTemplate 은 SQL 을 내가 직접 쓰고 결과를 객체로
 * 옮겨 담는 방식이다. SQL 이 눈에 보이므로 어떤 쿼리가 나가는지 숨겨지지 않는다.
 *
 * 값은 전부 ? 로 바인딩한다. 문자열을 이어 붙이지 않고 값으로 넘기면 드라이버가
 * 값과 문법을 분리해서 보내므로 입력에 따옴표나 OR 1=1 이 들어와도 문법이 되지 않는다.
 */
@Repository
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
        // Service 가 이미 화이트리스트로 걸러서 넘겼지만 여기서 한 번 더 본다.
        // 이 메서드를 나중에 다른 코드가 직접 부를 수도 있고, 그때 검사가 빠지면
        // ORDER BY 자리에 외부 문자열이 그대로 들어간다. 같은 검사를 두 겹으로 둔다.
        if (!java.util.Arrays.asList("l.Lid DESC", "l.created DESC NULLS LAST, l.Lid DESC",
                "l.Lprice ASC, l.Lid DESC", "l.Lprice DESC, l.Lid DESC").contains(orderBy)) {
            throw new IllegalArgumentException("Unsupported lecture order");
        }
        StringBuilder sql = new StringBuilder(
                "SELECT l.Lid AS lid, l.name, l.headline, l.picture, i.iname AS instructor, "
                        // LEVEL 은 Oracle 이 계층 질의에 쓰는 이름이라 그냥 쓰면 충돌한다.
                        // 큰따옴표로 감싸 컬럼명임을 분명히 한다. 자바 문자열 안이라 \" 가 된다.
                        + "l.category, l.\"LEVEL\" AS level_value, l.Lprice AS price, "
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
        // 위에서 검사를 통과한 고정 문자열만 붙는다.
        sql.append(" ORDER BY ").append(orderBy);
        // Oracle 12c 이상의 페이지 자르기 구문. 이 두 자리는 값이라 ? 로 바인딩된다.
        sql.append(" OFFSET ? ROWS FETCH NEXT ? ROWS ONLY");
        params.add(offset);
        params.add(size);
        return jdbcTemplate.query(sql.toString(), CARD_MAPPER, params.toArray());
    }

    public Optional<LectureDetail> findOpenById(int lid) {
        String sql = "SELECT l.Lid AS lid, l.user_id AS instructor_user_id, l.name, l.headline, l.summary, "
                + "l.category, l.\"LEVEL\" AS level_value, l.picture, l.preview, l.Lprice AS price, "
                + "i.iname, i.bio, i.field, i.career "
                + "FROM lectures l "
                + "LEFT JOIN instructors i ON i.user_id = l.user_id "
                // status = 'open' 을 SQL 에 박아 둔다. 비공개 강의는 조회 자체가 안 된다.
                // 다 가져온 뒤 자바에서 걸러 내면 걸러 내는 코드를 빠뜨렸을 때 바로 새어 나간다.
                + "WHERE l.Lid = ? AND l.status = 'open'";
        try {
            return Optional.of(jdbcTemplate.queryForObject(sql, this::mapDetail, lid));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    /**
     * 이 사용자가 이 강의를 수강 중인지 본다. 상세 조회의 권한 판정이 여기에 걸려 있다.
     *
     * user_id 와 Lid 를 둘 다 조건에 넣는 것이 핵심이다. Lid 만 보면 로그인한 사람
     * 누구에게나 열리고, user_id 만 보면 그 사람이 산 적 없는 강의도 열린다.
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
        // 보낸 조건만 WHERE 에 덧붙인다. 안 보낸 조건은 구문이 아예 생기지 않는다.
        if (keyword != null) {
            // ESCAPE 로 탈출 문자를 지정한다. 아래 toLike 의 주석과 짝이다.
            sql.append(" AND l.name LIKE ? ESCAPE '\\'");
            String like = toLike(keyword);
            params.add(like);
        }
        if (category != null) {
            sql.append(" AND l.category = ?");
            params.add(category);
        }
        if (level != null) {
            sql.append(" AND l.\"LEVEL\" = ?");
            params.add(level);
        }
    }

    /**
     * 검색어를 LIKE 패턴으로 바꾼다.
     *
     * LIKE 에서 % 는 "아무 글자나 여러 개", _ 는 "아무 글자 하나"를 뜻한다. 사용자가
     * % 를 검색하면 그게 와일드카드로 해석되어 전체 강의가 나온다. 그래서 세 글자를
     * 앞에 \ 를 붙여 평범한 문자로 만든다. \ 자체를 먼저 바꿔야 뒤에 붙는 \ 가
     * 두 번 처리되지 않는다.
     *
     * 이건 SQL 인젝션 방어가 아니다. 그쪽은 ? 바인딩이 막는다. 여기는 검색 결과가
     * 의도와 다르게 나오는 것을 막는 처리다.
     */
    static String toLike(String keyword) {
        String escaped = keyword
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        return "%" + escaped + "%";
    }
}
