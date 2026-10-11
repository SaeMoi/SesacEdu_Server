package com.example.sesacedu.instructor.apply;

import com.example.sesacedu.instructor.apply.dto.ApplyStatusResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@Tag(name = "강사 신청")
@RestController
@RequestMapping("/instructor")
public class InstructorApplyController {

    private final InstructorApplyService applyService;

    public InstructorApplyController(InstructorApplyService applyService) {
        this.applyService = applyService;
    }

    @Operation(summary = "강사 신청 상태 조회",
            description = "강사 신청 상태(NONE/PENDING/APPROVED)를 반환한다. "
                    + "uid는 추후 JWT에서 추출할 예정이며, 현재는 임시로 파라미터로 받는다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "404", description = "사용자 정보 없음"),
            @ApiResponse(responseCode = "500", description = "서버 에러")
    })
    @GetMapping("/apply")
    public ResponseEntity<ApplyStatusResponse> getApplyStatus(
            // TODO: 인증 구현 후 이 파라미터를 제거하고 JWT 에서 uid 를 꺼낸다.
            @Parameter(description = "임시 사용자 번호 (추후 JWT 로 대체)")
            @RequestParam int uid) {
        try {
            return ResponseEntity.ok(applyService.getStatus(uid));
        } catch (InstructorApplyService.UserNotFoundException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "사용자 정보 없음");
        }
    }
}