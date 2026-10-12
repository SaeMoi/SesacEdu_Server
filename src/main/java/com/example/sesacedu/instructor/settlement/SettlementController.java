package com.example.sesacedu.instructor.settlement;

import com.example.sesacedu.instructor.settlement.dto.SettlementDetail;
import com.example.sesacedu.instructor.settlement.dto.SettlementListResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@Tag(name = "정산 관리")
@RestController
@RequestMapping("/instructor/settlement")
public class SettlementController {

    private final SettlementService settlementService;

    public SettlementController(SettlementService settlementService) {
        this.settlementService = settlementService;
    }

    // ---------------- 목록 ----------------
    @Operation(summary = "정산 목록 조회",
            description = "강사의 월별 정산 목록과 상단 요약(총매출/정산예정/최신상태)을 조회한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "500", description = "서버 에러")
    })
    @GetMapping
    public ResponseEntity<SettlementListResponse> getList(
            @Parameter(description = "임시 강사 사용자 번호 (추후 JWT 로 대체)")
            @RequestParam("uid") int instructorUserId) {
        return ResponseEntity.ok(settlementService.getList(instructorUserId));
    }

    // ---------------- 상세 ----------------
    @Operation(summary = "정산 상세 조회",
            description = "특정 월 정산의 합계·지급정보와 강의별 내역을 조회한다. "
                    + "강의별 내역은 해당 월 결제 완료분을 집계하고 공제율로 계산한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "404", description = "정산 없음 또는 본인 것 아님"),
            @ApiResponse(responseCode = "500", description = "서버 에러")
    })
    @GetMapping("/{sid}")
    public ResponseEntity<SettlementDetail> getDetail(
            @PathVariable("sid") int sid,
            @Parameter(description = "임시 강사 사용자 번호 (추후 JWT 로 대체)")
            @RequestParam("uid") int instructorUserId) {
        try {
            return ResponseEntity.ok(settlementService.getDetail(sid, instructorUserId));
        } catch (SettlementService.SettlementNotFound e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "정산 정보 없음");
        }
    }
}