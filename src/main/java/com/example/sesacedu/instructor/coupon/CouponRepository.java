package com.example.sesacedu.instructor.coupon;

import com.example.sesacedu.instructor.coupon.dto.CouponListItem;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

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

    public int countCoupons(long instructorUserId) {
        Integer c = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM coupons WHERE user_id = ?", Integer.class, instructorUserId);
        return c == null ? 0 : c;
    }
}