package com.example.sesacedu.instructor.settlement;

import com.example.sesacedu.instructor.settlement.dto.SettlementListItem;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.YearMonth;
import java.util.List;

/**
 * 강사 정산 JDBC. (확정 DDL 기준)
 *   목록/헤더 : settlements 테이블 (user_id = 강사)
 *   강의별 내역 : order_items + orders + lectures 에서 기간별 매출 집계
 *     - 가정 1) status='paid' 주문만 매출로 집계
 *     - 가정 2) 정산 기간 = 전월 21일 00:00 ~ 당월 21일 00:00(미포함) → 당월 20일까지 포함
 */
@Repository
public class SettlementRepository {

    private final JdbcTemplate jdbcTemplate;

    public SettlementRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** period("2026-10") → [전월 21일 00:00, 당월 21일 00:00) 정산 주기 구간 */
    private static Timestamp[] cycleWindow(String period) {
        YearMonth ym = YearMonth.parse(period);                                           // 2026-10
        Timestamp start = Timestamp.valueOf(ym.minusMonths(1).atDay(21).atStartOfDay());  // 2026-09-21 00:00
        Timestamp end   = Timestamp.valueOf(ym.atDay(21).atStartOfDay());                 // 2026-10-21 00:00 (미포함)
        return new Timestamp[]{ start, end };
    }

    // ---------------- 목록 ----------------
    private static final RowMapper<SettlementListItem> LIST_MAPPER = (rs, n) ->
            new SettlementListItem(
                    rs.getInt("sid"),
                    rs.getString("period"),
                    rs.getLong("amount"),
                    rs.getString("status"));

    public List<SettlementListItem> findSettlements(long instructorUserId, int offset, int size) {
        return jdbcTemplate.query(
                "SELECT sid, period, amount, status FROM settlements " +
                        "WHERE user_id = ? ORDER BY period DESC " +
                        "OFFSET ? ROWS FETCH NEXT ? ROWS ONLY",
                LIST_MAPPER, instructorUserId, offset, size);
    }

    public int countSettlements(long instructorUserId) {
        Integer c = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM settlements WHERE user_id = ?", Integer.class, instructorUserId);
        return c == null ? 0 : c;
    }

    // 요약: 가장 최근(period 내림차순 1건)의 revenue/amount/status
    public Summary findLatestSummary(long instructorUserId) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT revenue, amount, status FROM settlements " +
                            "WHERE user_id = ? ORDER BY period DESC FETCH NEXT 1 ROWS ONLY",
                    (rs, n) -> new Summary(rs.getLong("revenue"), rs.getLong("amount"), rs.getString("status")),
                    instructorUserId);
        } catch (EmptyResultDataAccessException e) {
            return new Summary(0, 0, null);
        }
    }

    public static class Summary {
        public final long totalRevenue;
        public final long upcomingAmount;
        public final String latestStatus;
        public Summary(long totalRevenue, long upcomingAmount, String latestStatus) {
            this.totalRevenue = totalRevenue;
            this.upcomingAmount = upcomingAmount;
            this.latestStatus = latestStatus;
        }
    }

    // ---------------- 상세 헤더 ----------------
    public SettlementHeader findHeader(int sid, long instructorUserId) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT sid, period, status, amount, bank, account, paid_at " +
                            "FROM settlements WHERE sid = ? AND user_id = ?",
                    (rs, n) -> {
                        SettlementHeader h = new SettlementHeader();
                        h.sid = rs.getInt("sid");
                        h.period = rs.getString("period");
                        h.status = rs.getString("status");
                        h.totalAmount = rs.getLong("amount");
                        h.bank = rs.getString("bank");
                        h.account = rs.getString("account");
                        h.payDate = rs.getTimestamp("paid_at") == null ? null
                                : rs.getTimestamp("paid_at").toLocalDateTime().toLocalDate().toString();
                        return h;
                    }, sid, instructorUserId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    public static class SettlementHeader {
        public int sid;
        public String period;
        public String status;
        public long totalAmount;
        public String bank;
        public String account;
        public String payDate;
    }

    // ---------------- 상세: 강의별 매출 (정산 주기 내 paid 주문 합계) ----------------
    public static class LectureRevenue {
        public final String lectureName;
        public final long revenue;
        public LectureRevenue(String lectureName, long revenue) {
            this.lectureName = lectureName;
            this.revenue = revenue;
        }
    }

    public List<LectureRevenue> findLectureRevenue(long instructorUserId, String period, int offset, int size) {
        Timestamp[] w = cycleWindow(period);
        return jdbcTemplate.query(
                "SELECT l.name AS lecture_name, SUM(oi.price) AS revenue " +
                        "FROM order_items oi " +
                        "JOIN orders o   ON oi.oid = o.oid " +
                        "JOIN lectures l ON oi.lid = l.lid " +
                        "WHERE l.user_id = ? AND o.status = 'paid' " +
                        "  AND o.paid_at >= ? AND o.paid_at < ? " +   // 전월 21일 ~ 당월 21일(미포함)
                        "GROUP BY l.lid, l.name " +
                        "ORDER BY revenue DESC " +
                        "OFFSET ? ROWS FETCH NEXT ? ROWS ONLY",
                (rs, n) -> new LectureRevenue(rs.getString("lecture_name"), rs.getLong("revenue")),
                instructorUserId, w[0], w[1], offset, size);
    }

    public int countLectureRevenue(long instructorUserId, String period) {
        Timestamp[] w = cycleWindow(period);
        Integer c = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM (" +
                        "  SELECT l.lid FROM order_items oi " +
                        "  JOIN orders o   ON oi.oid = o.oid " +
                        "  JOIN lectures l ON oi.lid = l.lid " +
                        "  WHERE l.user_id = ? AND o.status = 'paid' " +
                        "    AND o.paid_at >= ? AND o.paid_at < ? " +
                        "  GROUP BY l.lid)",
                Integer.class, instructorUserId, w[0], w[1]);
        return c == null ? 0 : c;
    }
}