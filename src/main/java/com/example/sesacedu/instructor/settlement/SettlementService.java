package com.example.sesacedu.instructor.settlement;

import com.example.sesacedu.instructor.settlement.dto.SettlementDetail;
import com.example.sesacedu.instructor.settlement.dto.SettlementListResponse;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 정산 조회 로직.
 *
 * 월 합계(revenue/deduction/amount)는 settlements 저장값을 쓴다.
 * 강의별 내역(items)의 매출은 해당 월 order_items 집계(결제 완료분),
 * 공제/정산액은 공제율(DEDUCTION_RATE)로 계산한다.
 */
@Service
public class SettlementService {

    /** 공제율 20%. 설정값으로 빼도 된다. */
    private static final double DEDUCTION_RATE = 0.20;

    private final SettlementRepository settlementRepository;

    public SettlementService(SettlementRepository settlementRepository) {
        this.settlementRepository = settlementRepository;
    }

    /** 정산 목록 + 요약 */
    public SettlementListResponse getList(int instructorUserId) {
        List<SettlementListResponse.Item> items = settlementRepository.findList(instructorUserId);
        long totalRevenue = settlementRepository.sumRevenue(instructorUserId);
        long upcoming = settlementRepository.sumUpcoming(instructorUserId);
        String latest = settlementRepository.findLatestStatus(instructorUserId);
        return new SettlementListResponse(totalRevenue, upcoming, latest, items);
    }

    /** 정산 상세 */
    public SettlementDetail getDetail(int sid, int instructorUserId) {
        SettlementDetail d = settlementRepository.findDetail(sid, instructorUserId);
        if (d == null) {
            throw new SettlementNotFound();
        }
        // 강의별 내역: 해당 월 order_items 집계로 매출, 공제/정산액은 공제율로 계산.
        List<SettlementDetail.Item> items =
                settlementRepository.findRevenueByLecture(instructorUserId, d.getPeriod());
        for (SettlementDetail.Item item : items) {
            long deduction = Math.round(item.getRevenue() * DEDUCTION_RATE);
            item.setDeduction(deduction);
            item.setAmount(item.getRevenue() - deduction);
        }
        d.setItems(items);
        return d;
    }

    /** 정산 정보 없음 (404) */
    public static class SettlementNotFound extends RuntimeException {
    }
}