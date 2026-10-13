package com.example.sesacedu.instructor.qna;

import com.example.sesacedu.global.config.JwtUtil;
import com.example.sesacedu.instructor.qna.dto.QnaAnswerRequest;
import com.example.sesacedu.instructor.qna.dto.QnaDetail;
import com.example.sesacedu.instructor.qna.dto.QnaPageResponse;
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

@Tag(name = "강사 Q&A")
@RestController
@RequestMapping("/instructor")
public class QnaController {

    private final QnaService qnaService;
    private final JwtUtil jwtUtil;

    public QnaController(QnaService qnaService, JwtUtil jwtUtil) {
        this.qnaService = qnaService;
        this.jwtUtil = jwtUtil;
    }

    /** Authorization 헤더의 JWT 에서 강사 user_id 를 꺼낸다 (강사 권한 확인 포함). */
    private long instructorId(HttpServletRequest request) {
        Claims claims = jwtUtil.parse(request);        // 401: 토큰 없음/만료/위조
        jwtUtil.requireRole(claims, "INSTRUCTOR");     // 403: 강사 아님
        return jwtUtil.getUid(claims);
    }

    // ---------------- 목록 ----------------
    @Operation(summary = "Q&A 목록 조회",
            description = "로그인한 강사의 담당 강의 전체에서 질문을 최신순으로 조회한다. 페이지당 5개 고정.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "401", description = "인증 실패"),
            @ApiResponse(responseCode = "403", description = "권한 없음"),
            @ApiResponse(responseCode = "500", description = "서버 에러")
    })
    @GetMapping("/qna")
    public ResponseEntity<QnaPageResponse> getQnaList(
            @Parameter(description = "답변 상태 필터 (none / done)")
            @RequestParam(value = "status", required = false) String status,
            @Parameter(description = "페이지 번호 (기본 1)")
            @RequestParam(value = "page", defaultValue = "1") int page,
            HttpServletRequest request) {
        return ResponseEntity.ok(qnaService.getQnaList(instructorId(request), status, page));
    }

    // ---------------- 상세 ----------------
    @Operation(summary = "Q&A 상세 조회", description = "질문 내용과 기존 답변을 조회한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "404", description = "질문 없음 또는 권한 없음"),
            @ApiResponse(responseCode = "500", description = "서버 에러")
    })
    @GetMapping("/qna/{qid}")
    public ResponseEntity<QnaDetail> getDetail(
            @PathVariable("qid") int qid, HttpServletRequest request) {
        try {
            return ResponseEntity.ok(qnaService.getDetail(qid, instructorId(request)));
        } catch (QnaService.NotFoundOrForbidden e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "질문 정보 없음");
        }
    }

    // ---------------- 답변 저장 (작성·수정 공통) ----------------
    @Operation(summary = "답변 저장", description = "답변을 저장한다. 작성/수정 구분 없이 덮어쓴다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "저장 성공"),
            @ApiResponse(responseCode = "400", description = "필수 정보 누락"),
            @ApiResponse(responseCode = "404", description = "질문 없음 또는 권한 없음"),
            @ApiResponse(responseCode = "500", description = "서버 에러")
    })
    @PutMapping("/qna/{qid}")
    public ResponseEntity<Map<String, String>> saveAnswer(
            @PathVariable("qid") int qid,
            @RequestBody QnaAnswerRequest req,
            HttpServletRequest request) {
        long uid = instructorId(request);
        if (req.getAnswer() == null || req.getAnswer().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "답변 내용이 필요합니다.");
        }
        try {
            qnaService.saveAnswer(qid, uid, req.getAnswer());
        } catch (QnaService.NotFoundOrForbidden e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "질문 정보 없음");
        }
        return ResponseEntity.ok(Map.of("message", "답변이 저장되었습니다."));
    }

    // ---------------- 답변 삭제 ----------------
    @Operation(summary = "답변 삭제", description = "답변만 비운다. 질문 행은 유지된다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "삭제 성공"),
            @ApiResponse(responseCode = "404", description = "질문 없음 또는 권한 없음"),
            @ApiResponse(responseCode = "500", description = "서버 에러")
    })
    @DeleteMapping("/qna/{qid}")
    public ResponseEntity<Map<String, String>> deleteAnswer(
            @PathVariable("qid") int qid, HttpServletRequest request) {
        try {
            qnaService.deleteAnswer(qid, instructorId(request));
        } catch (QnaService.NotFoundOrForbidden e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "질문 정보 없음");
        }
        return ResponseEntity.ok(Map.of("message", "답변이 삭제되었습니다."));
    }
}