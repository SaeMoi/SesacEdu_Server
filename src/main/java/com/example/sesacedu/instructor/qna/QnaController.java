package com.example.sesacedu.instructor.qna;

import com.example.sesacedu.instructor.qna.dto.AnswerSaveRequest;
import com.example.sesacedu.instructor.qna.dto.QnaDetail;
import com.example.sesacedu.instructor.qna.dto.QnaListItem;
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

@Tag(name = "Q&A 관리")
@RestController
@RequestMapping("/instructor/qna")
public class QnaController {

    private final QnaService qnaService;

    public QnaController(QnaService qnaService) {
        this.qnaService = qnaService;
    }

    // ---------------- 목록 ----------------
    @Operation(summary = "Q&A 목록 조회",
            description = "담당 강의의 Q&A 목록을 조회한다. status(none/done)로 답변 상태를 필터링한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "500", description = "서버 에러")
    })
    @GetMapping
    public ResponseEntity<List<QnaListItem>> getList(
            @Parameter(description = "임시 강사 사용자 번호 (추후 JWT 로 대체)")
            @RequestParam("uid") int instructorUserId,
            @Parameter(description = "답변 상태 필터 (none: 미답변 / done: 답변완료). 생략 시 전체")
            @RequestParam(value = "status", required = false) String status) {
        return ResponseEntity.ok(qnaService.getList(instructorUserId, status));
    }

    // ---------------- 상세 ----------------
    @Operation(summary = "Q&A 상세 조회",
            description = "질문 내용과 기존 답변을 조회한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "404", description = "질문 없음 또는 권한 없음"),
            @ApiResponse(responseCode = "500", description = "서버 에러")
    })
    @GetMapping("/{qnaid}")
    public ResponseEntity<QnaDetail> getDetail(
            @PathVariable("qnaid") int qid,
            @Parameter(description = "임시 강사 사용자 번호 (추후 JWT 로 대체)")
            @RequestParam("uid") int instructorUserId) {
        try {
            return ResponseEntity.ok(qnaService.getDetail(qid, instructorUserId));
        } catch (QnaService.NotFoundOrForbidden e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "질문 정보 없음");
        }
    }

    // ---------------- 답변 저장 (작성·수정 공통) ----------------
    @Operation(summary = "답변 저장",
            description = "답변을 저장한다. 작성/수정 구분 없이 덮어쓰며, 답변자(answered_by)를 기록한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "저장 성공"),
            @ApiResponse(responseCode = "400", description = "필수 정보 누락"),
            @ApiResponse(responseCode = "404", description = "질문 없음 또는 권한 없음"),
            @ApiResponse(responseCode = "500", description = "서버 에러")
    })
    @PutMapping("/{qnaid}")
    public ResponseEntity<Map<String, String>> saveAnswer(
            @PathVariable("qnaid") int qid,
            @Parameter(description = "임시 강사 사용자 번호 (추후 JWT 로 대체)")
            @RequestParam("uid") int instructorUserId,
            @RequestBody AnswerSaveRequest req) {

        if (req.getAnswer() == null || req.getAnswer().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "답변 내용이 필요합니다.");
        }
        try {
            qnaService.saveAnswer(qid, instructorUserId, req.getAnswer());
        } catch (QnaService.NotFoundOrForbidden e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "질문 정보 없음");
        }
        return ResponseEntity.ok(Map.of("message", "답변이 저장되었습니다."));
    }

    // ---------------- 답변 삭제 ----------------
    @Operation(summary = "답변 삭제",
            description = "답변만 비운다. 질문 행은 유지된다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "삭제 성공"),
            @ApiResponse(responseCode = "404", description = "질문 없음 또는 권한 없음"),
            @ApiResponse(responseCode = "500", description = "서버 에러")
    })
    @DeleteMapping("/{qnaid}")
    public ResponseEntity<Map<String, String>> deleteAnswer(
            @PathVariable("qnaid") int qid,
            @Parameter(description = "임시 강사 사용자 번호 (추후 JWT 로 대체)")
            @RequestParam("uid") int instructorUserId) {
        try {
            qnaService.deleteAnswer(qid, instructorUserId);
        } catch (QnaService.NotFoundOrForbidden e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "질문 정보 없음");
        }
        return ResponseEntity.ok(Map.of("message", "답변이 삭제되었습니다."));
    }
}