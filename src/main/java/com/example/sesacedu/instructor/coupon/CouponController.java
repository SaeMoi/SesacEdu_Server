package com.example.sesacedu.instructor.coupon;

import com.example.sesacedu.global.config.JwtUtil;
import com.example.sesacedu.instructor.coupon.dto.CouponCreateRequest;
import com.example.sesacedu.instructor.coupon.dto.CouponPageResponse;
import io.jsonwebtoken.Claims;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;

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

    @Operation(summary = "쿠폰 발행",
            description = "로그인한 강사가 본인 강의에 쿠폰을 발행한다. JSON { Lid, cname, discount, due(yyyy-MM-dd), quantity }. "
                    + "code 를 비우면 서버가 12자리 코드를 만든다. 할인 금액은 1원 이상·강의 가격 이하, 종료일은 미래, 수량은 1 이상.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "발행 성공"),
            @ApiResponse(responseCode = "400", description = "입력값 오류"),
            @ApiResponse(responseCode = "401", description = "인증 실패"),
            @ApiResponse(responseCode = "403", description = "권한 없음"),
            @ApiResponse(responseCode = "404", description = "강의 없음 또는 본인 강의 아님"),
            @ApiResponse(responseCode = "409", description = "쿠폰 코드 중복"),
            @ApiResponse(responseCode = "500", description = "서버 에러")
    })
    @PostMapping("/coupon")
    public ResponseEntity<Map<String, Object>> createCoupon(
            @RequestBody(required = false) CouponCreateRequest body,
            HttpServletRequest request) {
        long uid = instructorId(request);
        CouponService.Created created = couponService.create(uid, body == null ? new CouponCreateRequest() : body);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("message", "쿠폰이 발행되었습니다.");
        result.put("cid", created.getCid());
        result.put("code", created.getCode());
        return ResponseEntity.status(201).body(result);
    }
}
