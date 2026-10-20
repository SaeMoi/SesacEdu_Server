package com.example.sesacedu.instructor.settlement;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 강사 정산 JDBC. (확정 DDL 기준)
 *   정산 행 : settlements (user_id = 강사). 관리자가 확정한 월만 있다.
 *   매출   : order_items + orders + lectures 에서 직접 계산 (status='paid' 주문만, 환불·취소 제외)
 *     - 강사가 발행한 강의 쿠폰(coupons.Lid = 그 강의)의 할인만 그 강의 매출에서 뺀다.
 *     - 관리자가 발행한 전체 쿠폰(coupons.Lid IS NULL)과 포인트는 플랫폼이 부담하므로 강사 매출을 줄이지 않는다 (Kim 2026-10-20).
 */
@Repository
public class SettlementRepository {

    private final JdbcTemplate jdbcTemplate;

    public SettlementRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // ---------------- settlements 행 ----------------
    public static class SettlementRow {
        public int sid;
        public String period;
        public String status;
        public long revenue;
        public long amount;
        public String bank;
        public String account;
        public String payDate;
    }

    private static final String ROW_COLUMNS =
            "SELECT sid, period, status, revenue, amount, bank, account, paid_at FROM settlements ";

    private static SettlementRow mapRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        SettlementRow r = new SettlementRow();
        r.sid = rs.getInt("sid");
        r.period = rs.getString("period");
        r.status = rs.getString("status");
        r.revenue = rs.getLong("revenue");
        r.amount = rs.getLong("amount");
        r.bank = rs.getString("bank");
        r.account = rs.getString("account");
        r.payDate = rs.getTimestamp("paid_at") == null ? null
                : rs.getTimestamp("paid_at").toLocalDateTime().toLocalDate().toString();
        return r;
    }

    public List<SettlementRow> findSettlements(long instructorUserId) {
        return jdbcTemplate.query(ROW_COLUMNS + "WHERE user_id = ?",
                (rs, n) -> mapRow(rs), instructorUserId);
    }

    public SettlementRow findSettlement(int sid, long instructorUserId) {
        try {
            return jdbcTemplate.queryForObject(ROW_COLUMNS + "WHERE sid = ? AND user_id = ?",
                    (rs, n) -> mapRow(rs), sid, instructorUserId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 정산 행이 아직 없는 달은 강사 정보의 계좌를 보여 준다. [bank, account] */
    public String[] findInstructorAccount(long instructorUserId) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT bank, account FROM instructors WHERE user_id = ?",
                    (rs, n) -> new String[]{ rs.getString("bank"), rs.getString("account") },
                    instructorUserId);
        } catch (EmptyResultDataAccessException e) {
            return new String[]{ null, null };
        }
    }

    // ---------------- 결제 완료 주문의 강의별 순매출 ----------------
    public static class PaidItem {
        public final long lid;
        public final String lectureName;
        public final LocalDateTime paidAt;
        public final long net;   // 쿠폰 할인을 뺀 금액
        public PaidItem(long lid, String lectureName, LocalDateTime paidAt, long net) {
            this.lid = lid;
            this.lectureName = lectureName;
            this.paidAt = paidAt;
            this.net = net;
        }
    }

    public List<PaidItem> findPaidItems(long instructorUserId) {
        return jdbcTemplate.query(
                "SELECT x.Lid, l.name AS lecture_name, x.paid_at, " +
                        // 강사 쿠폰(그 강의 전용)만 빼고, 전체 쿠폰(cpn_lid NULL)은 빼지 않는다
                        "       GREATEST(x.price - CASE WHEN x.cpn_lid = x.Lid THEN x.discount ELSE 0 END, 0) AS net " +
                        "FROM (SELECT oi.Lid, oi.price, o.paid_at, o.discount, c.Lid AS cpn_lid " +
                        "        FROM order_items oi " +
                        "        JOIN orders o ON o.oid = oi.oid " +
                        "        LEFT JOIN coupons c ON c.cid = o.cid " +
                        "       WHERE o.status = 'paid' AND o.paid_at IS NOT NULL) x " +
                        "JOIN lectures l ON l.Lid = x.Lid " +
                        "WHERE l.user_id = ?",
                (rs, n) -> new PaidItem(
                        rs.getLong("Lid"),
                        rs.getString("lecture_name"),
                        rs.getTimestamp("paid_at").toLocalDateTime(),
                        rs.getLong("net")),
                instructorUserId);
    }
}
