package com.example.sesacedu.instructor.coupon;

import com.example.sesacedu.global.exception.ApiException;
import com.example.sesacedu.instructor.coupon.dto.CouponCreateRequest;
import com.example.sesacedu.instructor.coupon.dto.CouponListItem;
import com.example.sesacedu.instructor.coupon.dto.CouponPageResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * 강사 쿠폰 로직.
 *   - 목록: 본인(user_id)이 발행한 쿠폰을 페이지네이션으로 조회한다.
 *   - 발행: 강사는 본인 강의 전용 쿠폰만 만든다 (Kim 2026-10-19).
 *     Lid 가 NULL 인 전체 강의(마스터) 쿠폰은 관리자 몫이다. 만들 때 min_amount >= discount 로 두어
 *     결제 금액보다 큰 할인이 나오지 않게 한다 (결제 쪽 buy.quote 도 min_amount 와 할인 상한을 검사한다).
 *   - 기한과 수량은 강사가 화면에서 정한다. 서버는 미래 날짜·1 이상만 확인한다.
 *   - 강사는 본인이 발행한 쿠폰을 등록·사용할 수 없다 (mypage cpn / buy.quote 에서 막는다).
 */
@Service
public class CouponService {

    private static final int PAGE_SIZE = 5;   // 페이지당 고정
    private static final String CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final CouponRepository couponRepository;

    public CouponService(CouponRepository couponRepository) {
        this.couponRepository = couponRepository;
    }

    /** 목록 (페이지네이션) */
    public CouponPageResponse getCoupons(long instructorUserId, int page) {
        int safePage = Math.max(page, 1);
        int offset = (safePage - 1) * PAGE_SIZE;

        List<CouponListItem> items = couponRepository.findCoupons(instructorUserId, offset, PAGE_SIZE);
        int total = couponRepository.countCoupons(instructorUserId);
        int totalPages = total == 0 ? 0 : (int) Math.ceil((double) total / PAGE_SIZE);

        return new CouponPageResponse(safePage, PAGE_SIZE, total, totalPages, items);
    }

    /**
     * 발행. 검증 순서: 필수값 → 강의(본인 강의) → 할인 금액 → 종료일 → 수량 → 코드.
     * @return 생성된 cid 와 등록 코드
     */
    public Created create(long instructorUserId, CouponCreateRequest req) {
        String cname = req.getCname() == null ? "" : req.getCname().trim();
        if (cname.isEmpty() || cname.length() > 100) {
            throw invalid("쿠폰명은 1~100자로 입력해 주세요.");
        }

        // 1. 적용 강의. 본인 강의만 고를 수 있다. 전체 강의(마스터) 쿠폰은 관리자가 발행한다.
        if (req.getLid() == null) {
            throw invalid("쿠폰을 적용할 강의를 선택해 주세요.");
        }
        Long price = couponRepository.findOwnLecturePrice(req.getLid(), instructorUserId);
        if (price == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "LECTURE_NOT_FOUND", "강의 정보가 없거나 접근 권한이 없습니다.");
        }

        // 2. 할인 금액: 1원 이상, 강의 가격 이하. 강의 전용 쿠폰이라 min_amount 는 쓰지 않는다.
        long discount = req.getDiscount() == null ? 0 : req.getDiscount();
        if (discount <= 0) {
            throw invalid("할인 금액은 1원 이상이어야 합니다.");
        }
        if (discount > price) {
            throw invalid("할인 금액은 강의 가격(" + price + "원)을 넘을 수 없습니다.");
        }

        // 3. 종료일: 강사가 정한다. 오늘 이후만 받고 그날 23:59:59 까지 쓸 수 있다.
        LocalDate dueDate;
        try {
            dueDate = LocalDate.parse(req.getDue() == null ? "" : req.getDue().trim());
        } catch (DateTimeParseException e) {
            throw invalid("종료일은 yyyy-MM-dd 형식으로 보내 주세요.");
        }
        if (!dueDate.isAfter(LocalDate.now())) {
            throw invalid("종료일은 오늘보다 미래 날짜여야 합니다.");
        }
        Timestamp due = Timestamp.valueOf(dueDate.atTime(LocalTime.of(23, 59, 59)));

        // 4. 수량: 강사가 정한다. 1 이상.
        Integer quantity = req.getQuantity();
        if (quantity == null || quantity <= 0) {
            throw invalid("수량은 1 이상이어야 합니다.");
        }

        // 5. 등록 코드: 안 보내면 서버가 12자리 무작위 코드를 만든다.
        //    수강생 쿠폰 등록(/mypage/cpn)이 받는 형식(영문 대문자·숫자 12~16자)에 맞춘다.
        String code = req.getCode() == null || req.getCode().isBlank()
                ? newCode()
                : req.getCode().replaceAll("[\\s-]", "").toUpperCase();
        if (!code.matches("^[A-Z0-9]{12,16}$")) {
            throw invalid("쿠폰 코드는 영문 대문자와 숫자 12~16자여야 합니다.");
        }
        if (couponRepository.codeExists(code)) {
            throw new ApiException(HttpStatus.CONFLICT, "COUPON_CODE_DUPLICATED", "이미 사용 중인 쿠폰 코드입니다.");
        }

        long cid = couponRepository.insert(instructorUserId, req.getLid(), cname, code, discount,
                req.getMinAmount(), quantity, due);
        return new Created(cid, code);
    }

    private String newCode() {
        for (int attempt = 0; attempt < 5; attempt++) {
            StringBuilder sb = new StringBuilder(12);
            for (int i = 0; i < 12; i++) sb.append(CODE_CHARS.charAt(RANDOM.nextInt(CODE_CHARS.length())));
            String code = sb.toString();
            if (!couponRepository.codeExists(code)) return code;
        }
        throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "COUPON_CODE_FAILED", "쿠폰 코드를 만들지 못했습니다.");
    }

    private static ApiException invalid(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_INPUT", message);
    }

    /** 발행 결과 */
    public static class Created {
        private final long cid;
        private final String code;

        public Created(long cid, String code) {
            this.cid = cid;
            this.code = code;
        }

        public long getCid() { return cid; }
        public String getCode() { return code; }
    }
}
