package com.example.sesacedu.instructor.coupon;

import com.example.sesacedu.instructor.coupon.dto.CouponListItem;
import com.example.sesacedu.instructor.coupon.dto.CouponPageResponse;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 강사 쿠폰 로직. 본인(user_id)이 발행한 쿠폰을 페이지네이션으로 조회한다.
 */
@Service
public class CouponService {

    private static final int PAGE_SIZE = 5;   // 페이지당 고정

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
}