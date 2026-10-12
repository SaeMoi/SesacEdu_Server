package com.example.sesacedu.instructor.settlement;

import com.example.sesacedu.instructor.settlement.dto.SettlementDetail;
import com.example.sesacedu.instructor.settlement.dto.SettlementListResponse;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 정산 JDBC.
 * 월 합계는 settlements 저장값, 강의별 매출은 order_items 집계로 구한다.
 */
@Repository
public class SettlementRepository {

    private final JdbcTemplate jdbcTemplate;

    public SettlementRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // ---------------- 목록 ----------------

    private static final RowMapper<SettlementListResponse.Item> LIST_MAPPER = (rs, n) ->
            new SettlementListResponse.Item(
                    rs.getInt("sid"),
                    rs.getString("period"),
                    rs.getLong("amount"),
                    rs.getString("status"));

    /** 강사의 월별 정산 목록 (최신순). */
    public List<SettlementListResponse.Item> findList(int instructorUserId) {
        return jdbcTemplate.query(
                "SELECT sid, period, amount, status FROM settlements " +
                        "WHERE user_id = ? ORDER BY period DESC",
                LIST_MAPPER, instructorUserId);
    }

    /** 전체 매출 합계 (요약용). 없으면 0. */
    public long sumRevenue(int instructorUserId) {
        Long v = jdbcTemplate.queryForObject(
                "SELECT NVL(SUM(revenue), 0) FROM settlements WHERE user_id = ?",
                Long.class, instructorUserId);
        return v == null ? 0 : v;
    }

    /** 정산 예정액 = requested 상태 amount 합. 없으면 0. */
    public long sumUpcoming(int instructorUserId) {
        Long v = jdbcTemplate.queryForObject(
                "SELECT NVL(SUM(amount), 0) FROM settlements " +
                        "WHERE user_id = ? AND status = 'requested'",
                Long.class, instructorUserId);
        return v == null ? 0 : v;
    }

    /** 최신 정산 상태 (period 최신). 없으면 null. */
    public String findLatestStatus(int instructorUserId) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT status FROM settlements WHERE user_id = ? " +
                            "ORDER BY period DESC FETCH FIRST 1 ROWS ONLY",
                    String.class, instructorUserId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    // ---------------- 상세 ----------------

    private static final RowMapper<SettlementDetail> DETAIL_MAPPER = (rs, n) -> {
        SettlementDetail d = new SettlementDetail();
        d.setSid(rs.getInt("sid"));
        d.setPeriod(rs.getString("period"));
        d.setStatus(rs.getString("status"));
        d.setRevenue(rs.getLong("revenue"));
        d.setDeduction(rs.getLong("deduction"));
        d.setAmount(rs.getLong("amount"));
        d.setBank(rs.getString("bank"));
        d.setAccount(rs.getString("account"));
        return d;
    };

    /**
     * 정산 상세(월 합계 + 지급 계좌). 본인(instructorUserId) 정산만 조회.
     * @return 없거나 본인 것 아니면 null
     */
    public SettlementDetail findDetail(int sid, int instructorUserId) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT s.sid, s.period, s.status, s.revenue, s.deduction, s.amount, " +
                            "       i.bank, i.account " +
                            "FROM settlements s " +
                            "JOIN instructors i ON s.user_id = i.user_id " +
                            "WHERE s.sid = ? AND s.user_id = ?",
                    DETAIL_MAPPER, sid, instructorUserId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /**
     * 강사의 강의별 매출 집계 — 특정 정산월(period)에 결제 완료된 주문만.
     *
     * 정산 기간 규칙: period 가 'YYYY.MM' 이면 그 정산월의 집계 대상은
     *   (전월 21일 00:00) ~ (당월 21일 00:00) 직전,  즉 전월 21일 ~ 당월 20일.
     * 예) period=2026.10  ->  2026-09-21 00:00 <= created < 2026-10-21 00:00
     *
     * period 문자열에서 당월 1일 DATE 를 만든 뒤:
     *   시작 = 당월1일 - 10일   (= 전월 21일)
     *   끝   = 다음달1일 - 10일  (= 당월 21일, 미만 비교)
     * order_items 를 강의명으로 묶어 price 합을 구한다.
     *
     * @return [강의명, 매출]만 채워진 Item 목록
     */
    public List<SettlementDetail.Item> findRevenueByLecture(int instructorUserId, String period) {
        return jdbcTemplate.query(
                "SELECT oi.lname AS lecture_name, SUM(oi.price) AS revenue " +
                        "FROM order_items oi " +
                        "JOIN orders o ON oi.oid = o.oid " +
                        "JOIN lectures l ON oi.lid = l.lid " +
                        "WHERE l.user_id = ? " +
                        "  AND o.status = 'paid' " +
                        // 당월 1일 (period 'YYYY.MM' -> DATE)
                        "  AND o.created >= (TO_DATE(? || '.01', 'YYYY.MM.DD') - 10) " +        // 전월 21일
                        "  AND o.created <  (ADD_MONTHS(TO_DATE(? || '.01', 'YYYY.MM.DD'), 1) - 10) " + // 당월 21일 미만
                        "GROUP BY oi.lname ORDER BY revenue DESC",
                (rs, n) -> {
                    SettlementDetail.Item item = new SettlementDetail.Item();
                    item.setLectureName(rs.getString("lecture_name"));
                    item.setRevenue(rs.getLong("revenue"));
                    return item;   // deduction/amount 는 서비스에서 계산
                },
                instructorUserId, period, period);
    }
}