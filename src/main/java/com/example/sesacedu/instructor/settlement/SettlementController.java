package com.example.sesacedu.instructor.settlement;

import com.example.sesacedu.global.config.JwtUtil;
import com.example.sesacedu.instructor.settlement.dto.SettlementDetailResponse;
import com.example.sesacedu.instructor.settlement.dto.SettlementListResponse;
import io.jsonwebtoken.Claims;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import javax.servlet.http.HttpServletRequest;

@Tag(name = "강사 정산")
@RestController
@RequestMapping("/instructor")
public class SettlementController {

    private final SettlementService settlementService;
    private final JwtUtil jwtUtil;

    public SettlementController(SettlementService settlementService, JwtUtil jwtUtil) {
        this.settlementService = settlementService;
        this.jwtUtil = jwtUtil;
    }

    /** Authorization 헤더의 JWT 에서 강사 user_id 를 꺼낸다 (강사 권한 확인 포함). */
    private long instructorId(HttpServletRequest request) {
        Claims claims = jwtUtil.parse(request);        // 401: 토큰 없음/만료/위조
        jwtUtil.requireRole(claims, "INSTRUCTOR");     // 403: 강사 아님
        return jwtUtil.getUid(claims);
    }

    // ---------------- 목록 ----------------
    @Operation(summary = "정산 목록 조회",
            description = "로그인한 강사의 월별 정산 목록과 상단 요약(최근 정산 기준)을 조회한다. 페이지당 5개 고정.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "401", description = "인증 실패"),
            @ApiResponse(responseCode = "403", description = "권한 없음"),
            @ApiResponse(responseCode = "500", description = "서버 에러")
    })
    @GetMapping("/settlement")
    public ResponseEntity<SettlementListResponse> getList(
            @Parameter(description = "페이지 번호 (기본 1)")
            @RequestParam(value = "page", defaultValue = "1") int page,
            HttpServletRequest request) {
        return ResponseEntity.ok(settlementService.getList(instructorId(request), page));
    }

    // ---------------- 상세 ----------------
    @Operation(summary = "정산 상세 조회",
            description = "정산 합계·계좌 정보와 강의별 매출/공제/정산금액 내역을 조회한다. 강의별 내역은 페이지당 5개 고정.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "401", description = "인증 실패"),
            @ApiResponse(responseCode = "403", description = "권한 없음 (본인 정산 아님)"),
            @ApiResponse(responseCode = "404", description = "정산 정보 없음"),
            @ApiResponse(responseCode = "500", description = "서버 에러")
    })
    @GetMapping("/settlement/{sid}")
    public ResponseEntity<SettlementDetailResponse> getDetail(
            @PathVariable("sid") int sid,
            @Parameter(description = "강의별 내역 페이지 번호 (기본 1)")
            @RequestParam(value = "page", defaultValue = "1") int page,
            HttpServletRequest request) {
        try {
            return ResponseEntity.ok(settlementService.getDetail(sid, instructorId(request), page));
        } catch (SettlementService.NotFoundOrForbidden e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "정산 정보 없음");
        }
    }
}