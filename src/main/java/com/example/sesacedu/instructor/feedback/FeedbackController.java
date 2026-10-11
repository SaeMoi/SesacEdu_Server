package com.example.sesacedu.instructor.feedback;

import com.example.sesacedu.instructor.feedback.dto.AssignmentDetail;
import com.example.sesacedu.instructor.feedback.dto.AssignmentListItem;
import com.example.sesacedu.instructor.feedback.dto.FeedbackSaveRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@Tag(name = "과제 피드백")
@RestController
@RequestMapping("/instructor")
public class FeedbackController {

    private final FeedbackService feedbackService;

    public FeedbackController(FeedbackService feedbackService) {
        this.feedbackService = feedbackService;
    }

    // ---------------- 목록 ----------------
    @Operation(summary = "과제 피드백 목록 조회",
            description = "담당 강의의 학생 제출 과제 목록과 피드백 작성 여부를 조회한다. "
                    + "lecture(강의번호)를 주면 해당 강의만, 없으면 담당 전체 강의를 본다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "500", description = "서버 에러")
    })
    @GetMapping("/assignments")
    public ResponseEntity<List<AssignmentListItem>> getAssignments(
            @Parameter(description = "임시 강사 사용자 번호 (추후 JWT 로 대체)")
            @RequestParam("uid") int instructorUserId,
            @Parameter(description = "강의 번호 (선택). 없으면 담당 전체 강의")
            @RequestParam(value = "lecture", required = false) Integer lecture) {
        return ResponseEntity.ok(feedbackService.getAssignments(instructorUserId, lecture));
    }

    // ---------------- 상세 ----------------
    @Operation(summary = "과제 상세 조회",
            description = "제출 내용·첨부파일·기존 피드백을 조회한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "404", description = "과제 없음 또는 권한 없음"),
            @ApiResponse(responseCode = "500", description = "서버 에러")
    })
    @GetMapping("/assignments/{assid}")
    public ResponseEntity<AssignmentDetail> getDetail(
            @PathVariable("assid") int aid,
            @Parameter(description = "임시 강사 사용자 번호 (추후 JWT 로 대체)")
            @RequestParam("uid") int instructorUserId) {
        try {
            return ResponseEntity.ok(feedbackService.getDetail(aid, instructorUserId));
        } catch (FeedbackService.NotFoundOrForbidden e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "과제 정보 없음");
        }
    }

    // ---------------- 피드백 저장 (작성·수정 공통) ----------------
    @Operation(summary = "피드백 저장",
            description = "점수와 피드백을 저장한다. 작성/수정 구분 없이 덮어쓴다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "저장 성공"),
            @ApiResponse(responseCode = "400", description = "필수 정보 누락"),
            @ApiResponse(responseCode = "404", description = "과제 없음 또는 권한 없음"),
            @ApiResponse(responseCode = "500", description = "서버 에러")
    })
    @PutMapping("/assignments/{assid}")
    public ResponseEntity<Map<String, String>> saveFeedback(
            @PathVariable("assid") int aid,
            @Parameter(description = "임시 강사 사용자 번호 (추후 JWT 로 대체)")
            @RequestParam("uid") int instructorUserId,
            @RequestBody FeedbackSaveRequest req) {

        if (req.getFeedback() == null || req.getFeedback().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "피드백 내용이 필요합니다.");
        }
        try {
            feedbackService.saveFeedback(aid, instructorUserId, req.getScore(), req.getFeedback());
        } catch (FeedbackService.NotFoundOrForbidden e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "과제 정보 없음");
        }
        return ResponseEntity.ok(Map.of("message", "피드백이 저장되었습니다."));
    }

    // ---------------- 피드백 삭제 ----------------
    @Operation(summary = "피드백 삭제",
            description = "피드백/점수만 비운다. 제출물 행은 유지된다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "삭제 성공"),
            @ApiResponse(responseCode = "404", description = "과제 없음 또는 권한 없음"),
            @ApiResponse(responseCode = "500", description = "서버 에러")
    })
    @DeleteMapping("/assignments/{assid}")
    public ResponseEntity<Map<String, String>> deleteFeedback(
            @PathVariable("assid") int aid,
            @Parameter(description = "임시 강사 사용자 번호 (추후 JWT 로 대체)")
            @RequestParam("uid") int instructorUserId) {
        try {
            feedbackService.deleteFeedback(aid, instructorUserId);
        } catch (FeedbackService.NotFoundOrForbidden e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "과제 정보 없음");
        }
        return ResponseEntity.ok(Map.of("message", "피드백이 삭제되었습니다."));
    }
}