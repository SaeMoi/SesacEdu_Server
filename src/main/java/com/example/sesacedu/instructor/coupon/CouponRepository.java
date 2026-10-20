package com.example.sesacedu.instructor.coupon;

import com.example.sesacedu.instructor.coupon.dto.CouponListItem;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.List;

/**
 * 강사 쿠폰 발행 JDBC. (확정 DDL 기준)
 *   coupons c (강사 본인이 발행) — user_id = 강사
 *   LEFT JOIN lectures l ON c.lid = l.lid  (강의명; 강의 연결 없거나 삭제돼도 쿠폰은 표시)
 */
@Repository
public class CouponRepository {

    private final JdbcTemplate jdbcTemplate;

    public CouponRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final RowMapper<CouponListItem> ITEM_MAPPER = (rs, n) -> {
        Object q = rs.getObject("quantity");
        return new CouponListItem(
                rs.getInt("cid"),
                rs.getString("lecture_name"),
                rs.getString("code"),
                rs.getLong("discount"),
                rs.getTimestamp("due") == null ? null
                        : rs.getTimestamp("due").toLocalDateTime().toLocalDate().toString(),
                rs.getInt("issued"),
                q == null ? null : ((Number) q).intValue()
        );
    };

    public List<CouponListItem> findCoupons(long instructorUserId, int offset, int size) {
        return jdbcTemplate.query(
                "SELECT c.cid, l.name AS lecture_name, c.code, c.discount, c.due, c.issued, c.quantity " +
                        "FROM coupons c " +
                        "LEFT JOIN lectures l ON c.lid = l.lid " +
                        "WHERE c.user_id = ? " +
                        "ORDER BY c.cid DESC " +
                        "OFFSET ? ROWS FETCH NEXT ? ROWS ONLY",
                ITEM_MAPPER, instructorUserId, offset, size);
    }

    /** 강사 본인 강의의 가격. 본인 강의가 아니거나 없으면 null. */
    public Long findOwnLecturePrice(long lid, long instructorUserId) {
        List<Long> rows = jdbcTemplate.queryForList(
                "SELECT Lprice FROM lectures WHERE Lid = ? AND user_id = ?",
                Long.class, lid, instructorUserId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 강사 본인 강의 중 가장 싼 가격 (전체 강의 쿠폰의 할인 상한 계산용). 강의가 없으면 null. */
    public Long findLowestOwnLecturePrice(long instructorUserId) {
        return jdbcTemplate.queryForObject(
                "SELECT MIN(Lprice) FROM lectures WHERE user_id = ?", Long.class, instructorUserId);
    }

    public boolean codeExists(String code) {
        Integer c = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM coupons WHERE code = ?", Integer.class, code);
        return c != null && c > 0;
    }

    /** 쿠폰 한 행을 넣고 cid 를 돌려준다. issued 는 기본값 0. */
    public long insert(long instructorUserId, Long lid, String cname, String code, long discount,
                       Long minAmount, Integer quantity, Timestamp due) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO coupons (user_id, Lid, cname, code, discount, min_amount, quantity, due, created) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, SYSTIMESTAMP)",
                    new String[]{"cid"});
            ps.setLong(1, instructorUserId);
            if (lid == null) ps.setNull(2, Types.NUMERIC); else ps.setLong(2, lid);
            ps.setString(3, cname);
            ps.setString(4, code);
            ps.setLong(5, discount);
            if (minAmount == null) ps.setNull(6, Types.NUMERIC); else ps.setLong(6, minAmount);
            if (quantity == null) ps.setNull(7, Types.NUMERIC); else ps.setInt(7, quantity);
            ps.setTimestamp(8, due);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return key == null ? 0 : key.longValue();
    }

    public int countCoupons(long instructorUserId) {
        Integer c = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM coupons WHERE user_id = ?", Integer.class, instructorUserId);
        return c == null ? 0 : c;
    }
}