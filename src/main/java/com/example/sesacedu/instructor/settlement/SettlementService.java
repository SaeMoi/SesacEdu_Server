package com.example.sesacedu.instructor.settlement;

import com.example.sesacedu.instructor.settlement.dto.SettlementDetailResponse;
import com.example.sesacedu.instructor.settlement.dto.SettlementLectureItem;
import com.example.sesacedu.instructor.settlement.dto.SettlementListItem;
import com.example.sesacedu.instructor.settlement.dto.SettlementListResponse;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 강사 정산 로직
 *   매출은 결제 완료 주문에서 바로 계산한다. settlements 행은 강사가 정산 신청한 달에만 생긴다 (자동 생성 없음).
 *   정산 주기: 매월 1일 00:00 ~ 말일 23:59:59 → period = 결제가 속한 그 달 (캘린더 월 기준)
 *   아직 settlements 행이 없는데 매출이 있는 달은 status = "pending"(정산 대기, 신청 전)으로 목록에 넣는다.
 *   정산 신청: 지난 달(이번 달 이전) + 매출 있음 + 신청 전 또는 반려된 달만 가능 → requestable.
 * 권한: 본인(user_id) 정산만 조회된다.
 */
@Service
public class SettlementService {

    private static final int PAGE_SIZE = 5;            // 페이지당 고정
    private static final double DEDUCTION_RATE = 0.20; // 플랫폼 수수료 20% (목업 기준 — 정책 확정 시 변경)
    private static final String STATUS_PENDING = "pending";
    private static final Pattern PERIOD = Pattern.compile("^(\\d{4})[.\\-/]?(\\d{1,2})$");
    private static final DateTimeFormatter PERIOD_OUT = DateTimeFormatter.ofPattern("yyyy.MM");
    private static final DateTimeFormatter PERIOD_DB = DateTimeFormatter.ofPattern("yyyy-MM"); // 신청 행 저장 형식 (관리자 서버 TO_CHAR 'YYYY-MM' 와 같음)
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final SettlementRepository repository;

    public SettlementService(SettlementRepository repository) {
        this.repository = repository;
    }

    /** "2026.09" / "2026-09" / "202609" 모두 받는다. 형식이 틀리면 null. */
    static YearMonth parsePeriod(String period) {
        if (period == null) return null;
        Matcher m = PERIOD.matcher(period.trim());
        if (!m.matches()) return null;
        int month = Integer.parseInt(m.group(2));
        if (month < 1 || month > 12) return null;
        return YearMonth.of(Integer.parseInt(m.group(1)), month);
    }

    /** 결제 시각이 속한 정산 월. 매월 1일~말일(캘린더 월) 기준 → 결제한 달 그대로. */
    static YearMonth cycleOf(LocalDateTime paidAt) {
        return YearMonth.from(paidAt);
    }

    private static long deductionOf(long revenue) {
        return Math.round(revenue * DEDUCTION_RATE);
    }

    /** 승인·지급된 달은 확정 금액(저장값)을, 그 외는 주문에서 계산한 금액을 쓴다. */
    private static boolean isFinalized(String status) {
        return "approved".equals(status) || "paid".equals(status);
    }

    /** 정산 신청 가능: 끝난 달(이번 달 이전) + 매출 있음 + 신청 전(행 없음) 또는 반려 */
    private static boolean isRequestable(YearMonth ym, String status, Month m) {
        boolean ended = ym.isBefore(YearMonth.now(KST));
        boolean hasRevenue = m != null && !m.revenue.isEmpty();
        return ended && hasRevenue && (STATUS_PENDING.equals(status) || "rejected".equals(status));
    }

    /** 정산 월별 강의 매출 합계 (lid → 강의명·매출) */
    private static class Month {
        final Map<Long, String> names = new HashMap<>();
        final Map<Long, Long> revenue = new LinkedHashMap<>();

        long amount() {
            long sum = 0;
            for (long r : revenue.values()) sum += r - deductionOf(r);
            return sum;
        }
    }

    private Map<YearMonth, Month> monthsOf(long instructorUserId) {
        Map<YearMonth, Month> months = new TreeMap<>(Comparator.reverseOrder());
        for (SettlementRepository.PaidItem item : repository.findPaidItems(instructorUserId)) {
            Month m = months.computeIfAbsent(cycleOf(item.paidAt), k -> new Month());
            m.names.put(item.lid, item.lectureName);
            m.revenue.merge(item.lid, item.net, Long::sum);
        }
        return months;
    }

    /** 목록 (요약 + 월별 목록) */
    public SettlementListResponse getList(long instructorUserId, int page) {
        Map<YearMonth, Month> months = monthsOf(instructorUserId);
        Map<YearMonth, SettlementRepository.SettlementRow> rows = new HashMap<>();
        for (SettlementRepository.SettlementRow row : repository.findSettlements(instructorUserId)) {
            YearMonth ym = parsePeriod(row.period);
            if (ym != null) rows.put(ym, row);
        }

        TreeMap<YearMonth, SettlementListItem> all = new TreeMap<>(Comparator.reverseOrder());
        for (Map.Entry<YearMonth, SettlementRepository.SettlementRow> e : rows.entrySet()) {
            SettlementRepository.SettlementRow row = e.getValue();
            Month m = months.get(e.getKey());
            long amount = isFinalized(row.status) ? row.amount : (m == null ? 0 : m.amount());
            all.put(e.getKey(), new SettlementListItem(row.sid, e.getKey().format(PERIOD_OUT), amount, row.status));
        }
        for (Map.Entry<YearMonth, Month> e : months.entrySet()) {
            if (!all.containsKey(e.getKey())) {
                all.put(e.getKey(), new SettlementListItem(null, e.getKey().format(PERIOD_OUT),
                        e.getValue().amount(), STATUS_PENDING));
            }
        }

        long totalRevenue = 0;
        for (Month m : months.values()) {
            for (long r : m.revenue.values()) totalRevenue += r;
        }
        long upcomingAmount = 0;
        for (SettlementListItem item : all.values()) {
            String s = item.getStatus();
            if (STATUS_PENDING.equals(s) || "requested".equals(s) || "approved".equals(s)) {
                upcomingAmount += item.getAmount();
            }
        }
        String latestStatus = all.isEmpty() ? null : all.firstEntry().getValue().getStatus();
        for (Map.Entry<YearMonth, SettlementListItem> e : all.entrySet()) {
            e.getValue().setRequestable(isRequestable(e.getKey(), e.getValue().getStatus(), months.get(e.getKey())));
        }

        List<SettlementListItem> list = new ArrayList<>(all.values());
        int safePage = Math.max(page, 1);
        int total = list.size();
        int totalPages = total == 0 ? 0 : (int) Math.ceil((double) total / PAGE_SIZE);
        int from = Math.min((safePage - 1) * PAGE_SIZE, total);
        List<SettlementListItem> items = list.subList(from, Math.min(from + PAGE_SIZE, total));

        return new SettlementListResponse(
                totalRevenue, upcomingAmount, latestStatus,
                safePage, PAGE_SIZE, total, totalPages, new ArrayList<>(items));
    }

    /**
     * 상세. key 는 정산 ID(숫자) 또는 정산 월("2026.10"). 정산 행이 없는 달은 매출이 있을 때만 보여 준다.
     * 없거나 본인 것 아니면 예외.
     */
    public SettlementDetailResponse getDetail(String key, long instructorUserId, int page) {
        SettlementRepository.SettlementRow row = null;
        YearMonth ym;
        if (key != null && key.matches("\\d{1,9}")) {
            row = repository.findSettlement(Integer.parseInt(key), instructorUserId);
            if (row == null) throw new NotFoundOrForbidden();
            ym = parsePeriod(row.period);
            if (ym == null) throw new NotFoundOrForbidden();
        } else {
            ym = parsePeriod(key);
            if (ym == null) throw new NotFoundOrForbidden();
            for (SettlementRepository.SettlementRow r : repository.findSettlements(instructorUserId)) {
                if (ym.equals(parsePeriod(r.period))) row = r;
            }
        }

        Month m = monthsOf(instructorUserId).get(ym);
        if (row == null && m == null) throw new NotFoundOrForbidden();

        List<SettlementLectureItem> lectures = new ArrayList<>();
        if (m != null) {
            m.revenue.entrySet().stream()
                    .sorted(Map.Entry.<Long, Long>comparingByValue().reversed())
                    .forEach(e -> {
                        long revenue = e.getValue();
                        long deduction = deductionOf(revenue);
                        lectures.add(new SettlementLectureItem(m.names.get(e.getKey()), revenue, deduction, revenue - deduction));
                    });
        }

        String status = row == null ? STATUS_PENDING : row.status;
        long totalAmount = row != null && isFinalized(row.status) ? row.amount : (m == null ? 0 : m.amount());
        String bank = row == null ? null : row.bank;
        String account = row == null ? null : row.account;
        if (bank == null && account == null) {
            String[] acc = repository.findInstructorAccount(instructorUserId);
            bank = acc[0];
            account = acc[1];
        }

        int safePage = Math.max(page, 1);
        int total = lectures.size();
        int totalPages = total == 0 ? 0 : (int) Math.ceil((double) total / PAGE_SIZE);
        int from = Math.min((safePage - 1) * PAGE_SIZE, total);

        return new SettlementDetailResponse(
                row == null ? null : row.sid, ym.format(PERIOD_OUT), status, totalAmount, bank, account,
                row == null ? null : row.payDate,
                safePage, PAGE_SIZE, total, totalPages,
                new ArrayList<>(lectures.subList(from, Math.min(from + PAGE_SIZE, total))));
    }

    /**
     * 정산 신청. period 는 "2026-09" / "2026.09" 모두 받는다.
     *   형식 오류·이번 달 이후·매출 없는 달 → InvalidRequest(400)
     *   이미 신청/승인/지급된 달 → AlreadyRequested(409)
     *   반려된 달 → 같은 행을 requested 로 되돌리고 금액 재계산 (재신청)
     * 금액은 신청 시점에 결제 완료 주문으로 다시 계산해 저장한다 (공제율 DEDUCTION_RATE).
     */
    @Transactional
    public SettlementListItem request(long instructorUserId, String period) {
        YearMonth ym = parsePeriod(period);
        if (ym == null) throw new InvalidRequest("정산 기간 형식이 올바르지 않습니다. (예: 2026-09)");
        if (!ym.isBefore(YearMonth.now(KST))) throw new InvalidRequest("정산 기간이 끝난 달만 신청할 수 있습니다.");

        Month m = monthsOf(instructorUserId).get(ym);
        if (m == null || m.revenue.isEmpty()) throw new InvalidRequest("해당 월에 정산할 매출이 없습니다.");

        long revenue = 0;
        for (long r : m.revenue.values()) revenue += r;
        long amount = m.amount();
        long deduction = revenue - amount;

        // 같은 달 기존 행 (저장 형식이 "2026.09"·"2026-09" 섞여 있어도 월로 비교)
        SettlementRepository.SettlementRow existing = null;
        for (SettlementRepository.SettlementRow r : repository.findSettlements(instructorUserId)) {
            if (ym.equals(parsePeriod(r.period))) existing = r;
        }

        String[] acc = repository.findInstructorAccount(instructorUserId);
        if (existing == null) {
            try {
                repository.insertRequest(instructorUserId, ym.format(PERIOD_DB), revenue, deduction, amount, acc[0], acc[1]);
            } catch (DuplicateKeyException e) {           // 동시에 두 번 신청 → UNIQUE(user_id, period)
                throw new AlreadyRequested();
            }
        } else if ("rejected".equals(existing.status)) {
            if (repository.reRequest(existing.sid, instructorUserId, revenue, deduction, amount, acc[0], acc[1]) == 0) {
                throw new AlreadyRequested();
            }
        } else {
            throw new AlreadyRequested();
        }

        Integer sid = existing != null ? existing.sid : null;
        if (sid == null) {
            for (SettlementRepository.SettlementRow r : repository.findSettlements(instructorUserId)) {
                if (ym.equals(parsePeriod(r.period))) sid = r.sid;
            }
        }
        return new SettlementListItem(sid, ym.format(PERIOD_OUT), amount, "requested");
    }

    /** 정산 신청 불가 (400). 메시지는 사유. */
    public static class InvalidRequest extends RuntimeException {
        public InvalidRequest(String message) {
            super(message);
        }
    }

    /** 이미 신청·승인·지급된 달 (409) */
    public static class AlreadyRequested extends RuntimeException {
    }

    /** 정산이 없거나(=404) 본인 것이 아님(=권한 없음). 컨트롤러에서 404 로 변환. */
    public static class NotFoundOrForbidden extends RuntimeException {
    }
}
