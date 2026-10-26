package com.example.sesacedu.instructor.lecture;

import com.example.sesacedu.global.config.JwtUtil;
import com.example.sesacedu.instructor.lecture.dto.LectureDetail;
import com.example.sesacedu.instructor.lecture.dto.LectureListItem;
import com.example.sesacedu.instructor.lecture.dto.LectureSaveRequest;
import com.example.sesacedu.instructor.lecture.dto.PageResponse;
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

import javax.servlet.http.HttpServletRequest;
import java.util.Map;

/*
 * ===================== [취약 진단용 - lecture] JWT 인증은 원본 유지 =====================
 *  (V-VALID/A04)  입력검증 제거   : 필수값/상태 화이트리스트/카테고리·난이도/강사 여부 검사 삭제
 *  (연계) 서비스: 소유권 제거(IDOR/A01), 업로드 확장자 검사 제거(임의 파일 업로드/A05)
 *         리포지토리: SQLi(A03), 미살균 저장→XSS(A03)
 * ====================================================================================
 */
@Tag(name = "강의 관리")
@RestController
@RequestMapping("/instructor/lectures")
public class LectureController {

    private final LectureService lectureService;
    private final JwtUtil jwtUtil;

    public LectureController(LectureService lectureService, JwtUtil jwtUtil) {
        this.lectureService = lectureService;
        this.jwtUtil = jwtUtil;
    }

    // ---------------- 목록 ----------------
    @Operation(summary = "강의 목록 조회", description = "본인 강의 목록과 수강생 수를 페이지 단위로 조회한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "401", description = "인증 실패"),
            @ApiResponse(responseCode = "500", description = "서버 에러")
    })
    @GetMapping
    public ResponseEntity<PageResponse<LectureListItem>> getList(
            HttpServletRequest request,
            @Parameter(description = "상태 필터 (open/closed/draft). 생략 시 전체")
            @RequestParam(value = "status", required = false) String status,
            @Parameter(description = "페이지 번호(1-base). 생략 시 1")
            @RequestParam(value = "page", required = false, defaultValue = "1") int page) {
        long instructorUserId = jwtUtil.getUid(request);   // JWT 유지
        return ResponseEntity.ok(lectureService.getList(instructorUserId, status, page));
    }

    // ---------------- 상세 ----------------
    @Operation(summary = "강의 상세 조회", description = "[취약] 소유권 검증 없이 조회(IDOR).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "401", description = "인증 실패"),
            @ApiResponse(responseCode = "404", description = "강의 없음"),
            @ApiResponse(responseCode = "500", description = "서버 에러")
    })
    @GetMapping("/{lid}")
    public ResponseEntity<LectureDetail> getDetail(
            @PathVariable("lid") int lid,
            HttpServletRequest request) {
        long instructorUserId = jwtUtil.getUid(request);
        try {
            return ResponseEntity.ok(lectureService.getDetail(lid, instructorUserId));
        } catch (LectureService.NotFoundOrForbidden e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "강의 정보 없음");
        }
    }

    // ---------------- 등록 ----------------
    @Operation(summary = "강의 등록", description = "[취약] 입력검증/강사 여부 검증 없음.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "등록 성공"),
            @ApiResponse(responseCode = "401", description = "인증 실패"),
            @ApiResponse(responseCode = "500", description = "서버 에러")
    })
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> create(
            HttpServletRequest request,
            @RequestParam(value = "name", required = false) String name,
            @RequestParam(value = "category", required = false) String category,
            @RequestParam(value = "level", required = false) String level,
            @RequestParam(value = "headline", required = false) String headline,
            @RequestParam(value = "summary", required = false) String summary,
            @RequestParam(value = "price", required = false) Integer price,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "thumbnail", required = false) MultipartFile thumbnail) {

        long instructorUserId = jwtUtil.getUid(request);
        // [필수값 검증 복원] 제목·카테고리·난이도·한 줄 소개·가격·상태 필수
        //   (상태 화이트리스트/카테고리·난이도 목록/강사 여부 검증은 복원하지 않음)
        if (isBlank(name) || isBlank(category) || isBlank(level)
                || isBlank(headline) || price == null || isBlank(status)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "제목, 카테고리, 난이도, 한 줄 소개, 가격, 상태는 필수입니다.");
        }
        LectureSaveRequest req = toRequest(name, category, level, headline, summary, price, status);
        int lid = lectureService.create(instructorUserId, req, thumbnail);
        return ResponseEntity.ok(Map.of("message", "강의가 저장되었습니다.", "lid", lid));
    }

    // ---------------- 수정 ----------------
    @Operation(summary = "강의 수정", description = "[취약] 입력검증/소유권 검증 없음.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "수정 성공"),
            @ApiResponse(responseCode = "401", description = "인증 실패"),
            @ApiResponse(responseCode = "404", description = "강의 없음"),
            @ApiResponse(responseCode = "500", description = "서버 에러")
    })
    @PutMapping(value = "/{lid}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, String>> update(
            @PathVariable("lid") int lid,
            HttpServletRequest request,
            @RequestParam(value = "name", required = false) String name,
            @RequestParam(value = "category", required = false) String category,
            @RequestParam(value = "level", required = false) String level,
            @RequestParam(value = "headline", required = false) String headline,
            @RequestParam(value = "summary", required = false) String summary,
            @RequestParam(value = "price", required = false) Integer price,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "thumbnail", required = false) MultipartFile thumbnail) {

        long instructorUserId = jwtUtil.getUid(request);
        // [필수값 검증 복원] 제목·카테고리·난이도·한 줄 소개·가격·상태 필수
        if (isBlank(name) || isBlank(category) || isBlank(level)
                || isBlank(headline) || price == null || isBlank(status)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "제목, 카테고리, 난이도, 한 줄 소개, 가격, 상태는 필수입니다.");
        }
        LectureSaveRequest req = toRequest(name, category, level, headline, summary, price, status);
        try {
            lectureService.update(lid, instructorUserId, req, thumbnail);
        } catch (LectureService.NotFoundOrForbidden e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "강의 정보 없음");
        }
        return ResponseEntity.ok(Map.of("message", "강의가 수정되었습니다."));
    }

    // ---------------- 자료 업로드 ----------------
    @Operation(summary = "강의 자료 업로드", description = "[취약] 소유권/확장자 검증 없음(임의 파일 업로드).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "업로드 성공"),
            @ApiResponse(responseCode = "401", description = "인증 실패"),
            @ApiResponse(responseCode = "404", description = "강의 없음"),
            @ApiResponse(responseCode = "500", description = "서버 에러")
    })
    @PutMapping(value = "/{lid}/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, String>> uploadFiles(
            @PathVariable("lid") int lid,
            HttpServletRequest request,
            @RequestParam(value = "video", required = false) MultipartFile video,
            @RequestParam(value = "material", required = false) MultipartFile material) {
        long instructorUserId = jwtUtil.getUid(request);
        try {
            lectureService.uploadFiles(lid, instructorUserId, video, material);
        } catch (LectureService.NotFoundOrForbidden e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "강의 정보 없음");
        }
        return ResponseEntity.ok(Map.of("message", "파일이 업로드되었습니다."));
    }

    // ---------------- 자료 삭제 ----------------
    @Operation(summary = "강의 자료 삭제", description = "[취약] 소유권 검증 없음.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "삭제 성공"),
            @ApiResponse(responseCode = "401", description = "인증 실패"),
            @ApiResponse(responseCode = "404", description = "강의 없음"),
            @ApiResponse(responseCode = "500", description = "서버 에러")
    })
    @DeleteMapping("/{lid}/files/{slot}")
    public ResponseEntity<Map<String, String>> deleteFile(
            @PathVariable("lid") int lid,
            @PathVariable("slot") String slot,
            HttpServletRequest request) {
        long instructorUserId = jwtUtil.getUid(request);
        try {
            lectureService.deleteFile(lid, instructorUserId, slot);
        } catch (LectureService.NotFoundOrForbidden e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "강의 정보 없음");
        }
        return ResponseEntity.ok(Map.of("message", "파일을 삭제했습니다."));
    }

    // ---------------- 과제 설정 ----------------
    @Operation(summary = "강의 과제 설정", description = "[취약] 소유권 검증 없음.")
    @PutMapping("/{lid}/assignment")
    public ResponseEntity<Map<String, String>> updateAssignment(
            @PathVariable("lid") int lid,
            @RequestBody(required = false) Map<String, String> body,
            HttpServletRequest request) {
        long instructorUserId = jwtUtil.getUid(request);
        Map<String, String> b = body == null ? Map.of() : body;
        try {
            lectureService.updateAssignment(lid, instructorUserId, b.get("title"), b.get("content"), b.get("fileType"));
        } catch (LectureService.NotFoundOrForbidden e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "강의 정보 없음");
        }
        return ResponseEntity.ok(Map.of("message", "과제를 저장했습니다."));
    }

    /** 폼 파라미터를 LectureSaveRequest 로 모은다. [V-VALID] trim/검증 없이 그대로 담는다(NPE 방지). */
    private LectureSaveRequest toRequest(String name, String category, String level,
                                         String headline, String summary, Integer price, String status) {
        LectureSaveRequest req = new LectureSaveRequest();
        req.setName(name);
        req.setCategory(category);
        req.setLevel(level);
        req.setHeadline(headline);
        req.setSummary(summary);
        req.setPrice(price);
        req.setStatus(status);
        return req;
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}