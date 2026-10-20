package com.example.sesacedu.instructor.apply;

import com.example.sesacedu.global.config.JwtUtil;
import com.example.sesacedu.instructor.apply.dto.ApplyStatusResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import javax.servlet.http.HttpServletRequest;
import java.util.Map;

@Tag(name = "강사 신청")
@RestController
@RequestMapping("/instructor")
public class InstructorApplyController {

    private final InstructorApplyService applyService;
    private final JwtUtil jwtUtil;                     // 추가: 토큰에서 user_id 추출용

    public InstructorApplyController(InstructorApplyService applyService, JwtUtil jwtUtil) {
        this.applyService = applyService;
        this.jwtUtil = jwtUtil;
    }

    // ---------------------------------------------------------------
    // 강사 신청 상태 조회  (user_id 는 JWT 에서 추출)
    // ---------------------------------------------------------------
    @Operation(summary = "강사 신청 상태 조회",
            description = "강사 신청 상태(NONE/PENDING/APPROVED)를 반환한다. user_id 는 JWT 에서 추출한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "401", description = "인증 실패"),
            @ApiResponse(responseCode = "404", description = "사용자 정보 없음"),
            @ApiResponse(responseCode = "500", description = "서버 에러")
    })
    @GetMapping("/apply")
    public ResponseEntity<ApplyStatusResponse> getApplyStatus(HttpServletRequest request) {
        long userId = jwtUtil.getUid(request);         // 토큰 검증 + user_id 추출
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
            description = "소개/전문분야/증빙파일을 받아 강사 신청을 한다. user_id 는 JWT 에서 추출한다. "
                    + "instructors 에 신청 행을 추가하고 auth 를 신청 대기(4)로 바꾼다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "신청 완료"),
            @ApiResponse(responseCode = "400", description = "필수 정보 누락/빈 파일"),
            @ApiResponse(responseCode = "401", description = "인증 실패"),
            @ApiResponse(responseCode = "404", description = "사용자 정보 없음"),
            @ApiResponse(responseCode = "409", description = "이미 신청했거나 신청 불가 상태"),
            @ApiResponse(responseCode = "500", description = "서버 에러")
    })
    @PostMapping(value = "/apply", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, String>> apply(
            HttpServletRequest request,                // uid 파라미터 제거 → 토큰에서 추출
            @RequestParam("introduce") String introduce,
            @RequestParam("subject") String subject,
            @RequestParam("career") MultipartFile career) {

        long userId = jwtUtil.getUid(request);

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