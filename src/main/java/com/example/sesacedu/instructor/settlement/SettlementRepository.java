package com.example.sesacedu.instructor.settlement;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/*
 * ===================== [취약 진단용 - settlement] JWT 인증은 원본 유지 =====================
 *  (V-AUTHZ/A01) findSettlement 의 'AND user_id = ?' 삭제 → 아무 sid 의 정산(매출/계좌) 열람(IDOR)
 *  (V-SQLI/A03)  sid/user_id 조건 바인딩 제거(문자열 연결)
 * ====================================================================================
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
        // [V-SQLI] user_id 바인딩 제거
        return jdbcTemplate.query(ROW_COLUMNS + "WHERE user_id = " + instructorUserId,
                (rs, n) -> mapRow(rs));
    }

    /** [V-AUTHZ] 'AND user_id = ?' 제거 → 아무 sid 의 정산이나 조회(IDOR). [V-SQLI] sid 연결. */
    public SettlementRow findSettlement(int sid, long instructorUserId) {
        try {
            return jdbcTemplate.queryForObject(ROW_COLUMNS + "WHERE sid = " + sid,
                    (rs, n) -> mapRow(rs));
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    public String[] findInstructorAccount(long instructorUserId) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT bank, account FROM instructors WHERE user_id = " + instructorUserId,
                    (rs, n) -> new String[]{ rs.getString("bank"), rs.getString("account") });
        } catch (EmptyResultDataAccessException e) {
            return new String[]{ null, null };
        }
    }

    // ---------------- 정산 신청 (강사가 월 단위로 요청) ----------------
    /** 새 정산 신청 행. period 는 관리자 서버와 같은 "YYYY-MM". 계좌는 신청 시점의 instructors 값을 복사. */
    public void insertRequest(long instructorUserId, String period, long revenue, long deduction, long amount,
                              String bank, String account) {
        jdbcTemplate.update(
                "INSERT INTO settlements (user_id, period, revenue, deduction, amount, status, requested, bank, account) " +
                        "VALUES (?, ?, ?, ?, ?, 'requested', SYSTIMESTAMP, ?, ?)",
                instructorUserId, period, revenue, deduction, amount, bank, account);
    }

    /** 반려된 정산 재신청: requested 로 되돌리고 금액을 다시 계산한 값으로, 반려·승인 기록은 비운다. 반려 상태일 때만 바뀐다. */
    public int reRequest(int sid, long instructorUserId, long revenue, long deduction, long amount,
                         String bank, String account) {
        return jdbcTemplate.update(
                "UPDATE settlements SET status = 'requested', requested = SYSTIMESTAMP, " +
                        "       revenue = ?, deduction = ?, amount = ?, bank = ?, account = ?, " +
                        "       rejected_at = NULL, reject_reason = NULL, approved_by = NULL, approved_at = NULL " +
                        "WHERE sid = ? AND user_id = ? AND status = 'rejected'",
                revenue, deduction, amount, bank, account, sid, instructorUserId);
    }

    // ---------------- 결제 완료 주문의 강의별 순매출 ----------------
    public static class PaidItem {
        public final long lid;
        public final String lectureName;
        public final LocalDateTime paidAt;
        public final long net;
        public PaidItem(long lid, String lectureName, LocalDateTime paidAt, long net) {
            this.lid = lid;
            this.lectureName = lectureName;
            this.paidAt = paidAt;
            this.net = net;
        }
    }

    public List<PaidItem> findPaidItems(long instructorUserId) {
        // [V-SQLI] user_id 바인딩 제거
        return jdbcTemplate.query(
                "SELECT x.Lid, l.name AS lecture_name, x.paid_at, " +
                        "       GREATEST(x.price - CASE WHEN x.cpn_lid = x.Lid THEN x.discount ELSE 0 END, 0) AS net " +
                        "FROM (SELECT oi.Lid, oi.price, o.paid_at, o.discount, c.Lid AS cpn_lid " +
                        "        FROM order_items oi " +
                        "        JOIN orders o ON o.oid = oi.oid " +
                        "        LEFT JOIN coupons c ON c.cid = o.cid " +
                        "       WHERE o.status = 'paid' AND o.paid_at IS NOT NULL) x " +
                        "JOIN lectures l ON l.Lid = x.Lid " +
                        "WHERE l.user_id = " + instructorUserId,
                (rs, n) -> new PaidItem(
                        rs.getLong("Lid"),
                        rs.getString("lecture_name"),
                        rs.getTimestamp("paid_at").toLocalDateTime(),
                        rs.getLong("net")));
    }
}