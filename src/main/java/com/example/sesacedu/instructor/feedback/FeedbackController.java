package com.example.sesacedu.instructor.feedback;

import com.example.sesacedu.global.config.JwtUtil;
import com.example.sesacedu.instructor.feedback.dto.AssignmentDetail;
import com.example.sesacedu.instructor.feedback.dto.AssignmentDownload;
import com.example.sesacedu.instructor.feedback.dto.AssignmentPageResponse;
import com.example.sesacedu.instructor.feedback.dto.FeedbackSaveRequest;
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
import java.util.Map;

/*
 * ===================== [취약 진단용 - feedback] JWT 인증은 원본 유지 =====================
 *  (V-VALID/A04) 입력검증 제거 : 피드백 필수값 검사 삭제
 *  (연계) 서비스/리포지토리 소유권 제거 → 다른 강사 과제 열람·다운로드·채점·삭제(IDOR/A01),
 *         SQLi(A03), 미살균 저장/반환 → XSS(A03)
 * ====================================================================================
 */
@Tag(name = "과제 피드백")
@RestController
@RequestMapping("/instructor")
public class FeedbackController {

    private final FeedbackService feedbackService;
    private final JwtUtil jwtUtil;
    private final com.example.sesacedu.student.lecture.FileResponses fileResponses;

    public FeedbackController(FeedbackService feedbackService, JwtUtil jwtUtil,
                              com.example.sesacedu.student.lecture.FileResponses fileResponses) {
        this.feedbackService = feedbackService;
        this.jwtUtil = jwtUtil;
        this.fileResponses = fileResponses;
    }

    // ---------------- 제출 파일 받기 ----------------
    @Operation(summary = "과제 제출 파일 받기", description = "[취약] 담당강의 검증 없이 제출 파일을 내려준다.")
    @GetMapping("/assignments/{assid}/file")
    public ResponseEntity<org.springframework.core.io.Resource> file(
            @PathVariable("assid") int aid, HttpServletRequest request) {
        try {
            return fileResponses.send(feedbackService.getFile(aid, instructorId(request)), true);
        } catch (FeedbackService.NotFoundOrForbidden e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "과제 정보 없음");
        } catch (FeedbackService.FileNotFound e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "제출 파일이 없습니다.");
        }
    }

    /** Authorization 헤더의 JWT 에서 강사 user_id 를 꺼낸다 (강사 권한 확인 포함). JWT 유지. */
    private long instructorId(HttpServletRequest request) {
        Claims claims = jwtUtil.parse(request);        // 401
        jwtUtil.requireRole(claims, "INSTRUCTOR");     // 403
        return jwtUtil.getUid(claims);
    }

    // ---------------- 목록 ----------------
    @Operation(summary = "과제 피드백 목록 조회", description = "제출 과제를 최신순으로 조회한다. 페이지당 5개 고정.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "401", description = "인증 실패"),
            @ApiResponse(responseCode = "403", description = "권한 없음"),
            @ApiResponse(responseCode = "500", description = "서버 에러")
    })
    @GetMapping("/assignments")
    public ResponseEntity<AssignmentPageResponse> getAssignments(
            @Parameter(description = "피드백 상태 필터 (none / done)")
            @RequestParam(value = "status", required = false) String status,
            @Parameter(description = "페이지 번호 (기본 1)")
            @RequestParam(value = "page", defaultValue = "1") int page,
            HttpServletRequest request) {
        return ResponseEntity.ok(feedbackService.getAssignments(instructorId(request), status, page));
    }

    // ---------------- 상세 ----------------
    @Operation(summary = "과제 상세 조회", description = "[취약] 담당강의 검증 없이 조회(IDOR).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "404", description = "과제 없음"),
            @ApiResponse(responseCode = "500", description = "서버 에러")
    })
    @GetMapping("/assignments/{assid}")
    public ResponseEntity<AssignmentDetail> getDetail(
            @PathVariable("assid") int aid, HttpServletRequest request) {
        try {
            return ResponseEntity.ok(feedbackService.getDetail(aid, instructorId(request)));
        } catch (FeedbackService.NotFoundOrForbidden e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "과제 정보 없음");
        }
    }

    // ---------------- 과제 파일 다운로드 ----------------
    @Operation(summary = "과제 파일 다운로드", description = "[취약] 담당강의 검증 없이 다운로드 URL 발급(IDOR).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "다운로드 URL 발급 성공"),
            @ApiResponse(responseCode = "404", description = "과제 없음/제출 파일 없음"),
            @ApiResponse(responseCode = "500", description = "서버 에러")
    })
    @GetMapping("/assignments/{assid}/download")
    public ResponseEntity<AssignmentDownload> download(
            @PathVariable("assid") int aid, HttpServletRequest request) {
        try {
            return ResponseEntity.ok(feedbackService.getDownload(aid, instructorId(request)));
        } catch (FeedbackService.NotFoundOrForbidden e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "과제 정보 없음");
        } catch (FeedbackService.FileNotFound e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "제출 파일이 없습니다.");
        }
    }

    // ---------------- 피드백 저장 (작성·수정 공통) ----------------
    @Operation(summary = "피드백 저장", description = "[취약] 입력검증·소유권검증 없이 저장.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "저장 성공"),
            @ApiResponse(responseCode = "404", description = "과제 없음"),
            @ApiResponse(responseCode = "500", description = "서버 에러")
    })
    @PutMapping("/assignments/{assid}")
    public ResponseEntity<Map<String, String>> saveFeedback(
            @PathVariable("assid") int aid,
            @RequestBody FeedbackSaveRequest req,
            HttpServletRequest request) {
        long uid = instructorId(request);
        // [필수값 검증 복원] 피드백 내용 필수
        if (req.getFeedback() == null || req.getFeedback().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "피드백 내용이 필요합니다.");
        }
        try {
            feedbackService.saveFeedback(aid, uid, req.getScore(), req.getFeedback());
        } catch (FeedbackService.NotFoundOrForbidden e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "과제 정보 없음");
        }
        return ResponseEntity.ok(Map.of("message", "피드백이 저장되었습니다."));
    }

    // ---------------- 피드백 삭제 ----------------
    @Operation(summary = "피드백 삭제", description = "[취약] 소유권검증 없이 삭제.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "삭제 성공"),
            @ApiResponse(responseCode = "404", description = "과제 없음"),
            @ApiResponse(responseCode = "500", description = "서버 에러")
    })
    @DeleteMapping("/assignments/{assid}")
    public ResponseEntity<Map<String, String>> deleteFeedback(
            @PathVariable("assid") int aid, HttpServletRequest request) {
        try {
            feedbackService.deleteFeedback(aid, instructorId(request));
        } catch (FeedbackService.NotFoundOrForbidden e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "과제 정보 없음");
        }
        return ResponseEntity.ok(Map.of("message", "피드백이 삭제되었습니다."));
    }
}