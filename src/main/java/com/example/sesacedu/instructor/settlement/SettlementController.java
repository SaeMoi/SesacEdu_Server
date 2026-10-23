package com.example.sesacedu.instructor.settlement;

import com.example.sesacedu.global.config.JwtUtil;
import com.example.sesacedu.instructor.settlement.dto.SettlementDetailResponse;
import com.example.sesacedu.instructor.settlement.dto.SettlementListItem;
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

    // ---------------- 정산 신청 ----------------
    @Operation(summary = "정산 신청",
            description = "로그인한 강사가 끝난 달(매월 1일 00:00 ~ 말일 23:59:59)의 정산을 신청한다. "
                    + "body { \"period\": \"2026-09\" } (2026.09 도 허용). 금액은 신청 시점의 결제 완료 주문으로 계산해 requested 로 저장한다. "
                    + "반려된 달은 다시 신청할 수 있다.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "신청 완료"),
            @ApiResponse(responseCode = "400", description = "기간 형식 오류 / 아직 끝나지 않은 달 / 매출 없는 달"),
            @ApiResponse(responseCode = "401", description = "인증 실패"),
            @ApiResponse(responseCode = "403", description = "권한 없음"),
            @ApiResponse(responseCode = "409", description = "이미 신청·승인·지급된 달"),
            @ApiResponse(responseCode = "500", description = "서버 에러")
    })
    @PostMapping("/settlement")
    public ResponseEntity<SettlementListItem> request(
            @RequestBody(required = false) SettlementRequest body,
            HttpServletRequest request) {
        long userId = instructorId(request);
        try {
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(settlementService.request(userId, body == null ? null : body.getPeriod()));
        } catch (SettlementService.InvalidRequest e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (SettlementService.AlreadyRequested e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 신청된 정산입니다.");
        }
    }

    /** 정산 신청 요청 본문 { period } */
    public static class SettlementRequest {
        private String period;   // "2026-09" (또는 "2026.09")

        public String getPeriod() { return period; }
        public void setPeriod(String period) { this.period = period; }
    }

    // ---------------- 상세 ----------------
    @Operation(summary = "정산 상세 조회",
            description = "정산 합계·계좌 정보와 강의별 매출/공제/정산금액 내역을 조회한다. "
                    + "sid 는 정산 ID 또는 정산 월(2026.10). 정산 행이 아직 없는 달은 정산 월로 조회한다. 강의별 내역은 페이지당 5개 고정.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "401", description = "인증 실패"),
            @ApiResponse(responseCode = "403", description = "권한 없음 (본인 정산 아님)"),
            @ApiResponse(responseCode = "404", description = "정산 정보 없음"),
            @ApiResponse(responseCode = "500", description = "서버 에러")
    })
    @GetMapping("/settlement/{sid}")
    public ResponseEntity<SettlementDetailResponse> getDetail(
            @PathVariable("sid") String sid,
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