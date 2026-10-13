package com.example.sesacedu.instructor.settlement;

import com.example.sesacedu.instructor.settlement.dto.SettlementDetailResponse;
import com.example.sesacedu.instructor.settlement.dto.SettlementLectureItem;
import com.example.sesacedu.instructor.settlement.dto.SettlementListItem;
import com.example.sesacedu.instructor.settlement.dto.SettlementListResponse;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 강사 정산 로직.
 *   - 목록(요약 + 월별 목록, 페이지네이션)
 *   - 상세(정산 합계·계좌 + 강의별 내역, 페이지네이션)
 * 권한: 본인(user_id) 정산만 조회된다.
 */
@Service
public class SettlementService {

    private static final int PAGE_SIZE = 5;          // 페이지당 고정
    private static final double DEDUCTION_RATE = 0.20; // 플랫폼 수수료 20% (목업 기준 — 정책 확정 시 변경)

    private final SettlementRepository repository;

    public SettlementService(SettlementRepository repository) {
        this.repository = repository;
    }

    /** 목록 (요약 + 월별 목록) */
    public SettlementListResponse getList(long instructorUserId, int page) {
        int safePage = Math.max(page, 1);
        int offset = (safePage - 1) * PAGE_SIZE;

        List<SettlementListItem> items = repository.findSettlements(instructorUserId, offset, PAGE_SIZE);
        int total = repository.countSettlements(instructorUserId);
        int totalPages = total == 0 ? 0 : (int) Math.ceil((double) total / PAGE_SIZE);

        SettlementRepository.Summary s = repository.findLatestSummary(instructorUserId);

        return new SettlementListResponse(
                s.totalRevenue, s.upcomingAmount, s.latestStatus,
                safePage, PAGE_SIZE, total, totalPages, items);
    }

    /** 상세 (정산 합계·계좌 + 강의별 내역). 없거나 본인 것 아니면 예외. */
    public SettlementDetailResponse getDetail(int sid, long instructorUserId, int page) {
        SettlementRepository.SettlementHeader h = repository.findHeader(sid, instructorUserId);
        if (h == null) {
            throw new NotFoundOrForbidden();
        }

        int safePage = Math.max(page, 1);
        int offset = (safePage - 1) * PAGE_SIZE;

        List<SettlementRepository.LectureRevenue> raw =
                repository.findLectureRevenue(instructorUserId, h.period, offset, PAGE_SIZE);

        List<SettlementLectureItem> lectures = new ArrayList<>();
        for (SettlementRepository.LectureRevenue r : raw) {
            long deduction = Math.round(r.revenue * DEDUCTION_RATE);
            long amount = r.revenue - deduction;
            lectures.add(new SettlementLectureItem(r.lectureName, r.revenue, deduction, amount));
        }

        int total = repository.countLectureRevenue(instructorUserId, h.period);
        int totalPages = total == 0 ? 0 : (int) Math.ceil((double) total / PAGE_SIZE);

        return new SettlementDetailResponse(
                h.sid, h.period, h.status, h.totalAmount, h.bank, h.account, h.payDate,
                safePage, PAGE_SIZE, total, totalPages, lectures);
    }

    /** 정산이 없거나(=404) 본인 것이 아님(=권한 없음). 컨트롤러에서 404 로 변환. */
    public static class NotFoundOrForbidden extends RuntimeException {
    }
}