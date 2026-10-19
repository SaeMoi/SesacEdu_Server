package com.example.sesacedu.instructor.coupon;

import com.example.sesacedu.global.config.JwtUtil;
import com.example.sesacedu.instructor.coupon.dto.CouponPageResponse;
import io.jsonwebtoken.Claims;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;

@Tag(name = "강사 쿠폰")
@RestController
@RequestMapping("/instructor")
public class CouponController {

    private final CouponService couponService;
    private final JwtUtil jwtUtil;

    public CouponController(CouponService couponService, JwtUtil jwtUtil) {
        this.couponService = couponService;
        this.jwtUtil = jwtUtil;
    }

    /** Authorization 헤더의 JWT 에서 강사 user_id 를 꺼낸다 (강사 권한 확인 포함). */
    private long instructorId(HttpServletRequest request) {
        Claims claims = jwtUtil.parse(request);        // 401: 토큰 없음/만료/위조
        jwtUtil.requireRole(claims, "INSTRUCTOR");     // 403: 강사 아님
        return jwtUtil.getUid(claims);
    }

    @Operation(summary = "쿠폰 발행 목록 조회",
            description = "로그인한 강사가 발행한 쿠폰을 최신순으로 조회한다. 페이지당 5개 고정.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "401", description = "인증 실패"),
            @ApiResponse(responseCode = "403", description = "권한 없음"),
            @ApiResponse(responseCode = "500", description = "서버 에러")
    })
    @GetMapping("/coupon")
    public ResponseEntity<CouponPageResponse> getCoupons(
            @Parameter(description = "페이지 번호 (기본 1)")
            @RequestParam(value = "page", defaultValue = "1") int page,
            HttpServletRequest request) {
        return ResponseEntity.ok(couponService.getCoupons(instructorId(request), page));
    }
}