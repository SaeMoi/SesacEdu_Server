package com.example.sesacedu.instructor.apply;

import com.example.sesacedu.instructor.apply.dto.ApplyStatusResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@Tag(name = "강사 신청")
@RestController
@RequestMapping("/instructor")
public class InstructorApplyController {

    private final InstructorApplyService applyService;

    public InstructorApplyController(InstructorApplyService applyService) {
        this.applyService = applyService;
    }

    // ---------------------------------------------------------------
    // 강사 신청 상태 조회
    // ---------------------------------------------------------------
    @Operation(summary = "강사 신청 상태 조회",
            description = "강사 신청 상태(NONE/PENDING/APPROVED)를 반환한다. "
                    + "user_id 는 추후 JWT 에서 추출 예정이며, 현재는 임시로 파라미터로 받는다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "404", description = "사용자 정보 없음"),
            @ApiResponse(responseCode = "500", description = "서버 에러")
    })
    @GetMapping("/apply")
    public ResponseEntity<ApplyStatusResponse> getApplyStatus(
            // TODO: 인증 구현 후 이 파라미터 제거하고 JWT 에서 user_id 추출
            @Parameter(description = "임시 사용자 번호 (추후 JWT 로 대체)")
            @RequestParam("uid") int userId) {
        try {
            return ResponseEntity.ok(applyService.getStatus(userId));
        } catch (InstructorApplyService.UserNotFoundException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "사용자 정보 없음");
        }
    }

    // ---------------------------------------------------------------
    // 강사 신청 (파일 업로드 포함, multipart/form-data)
    // ---------------------------------------------------------------
    @Operation(summary = "강사 신청",
            description = "소개/전문분야/증빙파일을 받아 강사 신청을 한다. "
                    + "instructors 에 신청 행을 추가하고 auth 를 신청 대기(4)로 바꾼다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "신청 완료"),
            @ApiResponse(responseCode = "400", description = "필수 정보 누락/빈 파일"),
            @ApiResponse(responseCode = "404", description = "사용자 정보 없음"),
            @ApiResponse(responseCode = "409", description = "이미 신청했거나 신청 불가 상태"),
            @ApiResponse(responseCode = "500", description = "서버 에러")
    })
    @PostMapping(value = "/apply", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, String>> apply(
            // TODO: 인증 구현 후 이 파라미터 제거하고 JWT 에서 user_id 추출
            @Parameter(description = "임시 사용자 번호 (추후 JWT 로 대체)")
            @RequestParam("uid") int userId,
            @RequestParam("introduce") String introduce,
            @RequestParam("subject") String subject,
            @RequestParam("career") MultipartFile career) {

        if (introduce == null || introduce.isBlank()
                || subject == null || subject.isBlank()
                || career == null || career.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "필수 정보 누락");
        }

        try {
            applyService.apply(userId, introduce, subject, career);
        } catch (InstructorApplyService.UserNotFoundException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "사용자 정보 없음");
        } catch (InstructorApplyService.AlreadyAppliedException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 신청했거나 신청할 수 없는 상태입니다.");
        }

        return ResponseEntity.ok(Map.of(
                "message", "강사 신청이 완료되었습니다.",
                "status", "PENDING"));
    }
}