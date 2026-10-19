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

@Tag(name = "강의 관리")
@RestController
@RequestMapping("/instructor/lectures")
public class LectureController {

    private final LectureService lectureService;
    private final JwtUtil jwtUtil;                     // 추가: 토큰에서 user_id 추출용

    public LectureController(LectureService lectureService, JwtUtil jwtUtil) {
        this.lectureService = lectureService;
        this.jwtUtil = jwtUtil;
    }

    // ---------------- 목록 ----------------
    @Operation(summary = "강의 목록 조회",
            description = "본인 강의 목록과 각 강의 수강생 수를 페이지 단위(한 페이지 5개)로 조회한다. "
                    + "user_id 는 JWT 에서 추출한다. status(open/closed/draft)로 공개 상태를 필터링한다. "
                    + "응답은 content 와 함께 page/size/totalElements/totalPages 를 포함한다.")
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
        long instructorUserId = jwtUtil.getUid(request);
        return ResponseEntity.ok(lectureService.getList(instructorUserId, status, page));
    }

    // ---------------- 상세 (수정 화면 진입) ----------------
    @Operation(summary = "강의 상세 조회",
            description = "수정 화면 진입 시 기존 값을 불러온다. user_id 는 JWT 에서 추출한다. 본인 강의만 조회된다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "401", description = "인증 실패"),
            @ApiResponse(responseCode = "404", description = "강의 없음 또는 권한 없음"),
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

    // ---------------- 등록 (기본정보 + 썸네일) ----------------
    @Operation(summary = "강의 등록",
            description = "기본 정보와 썸네일을 저장한다(25번 기본정보 화면). "
                    + "multipart/form-data. user_id 는 JWT 에서 추출한다. "
                    + "name·category·level·headline·price·status·thumbnail 은 필수, summary 만 선택. "
                    + "status 는 open/closed/draft 중 하나(필수)이며 요청 값 그대로 저장된다. "
                    + "영상·자료는 다음 화면의 파일 업로드 API 로 올린다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "등록 성공"),
            @ApiResponse(responseCode = "400", description = "필수 정보 누락 / 썸네일 형식 오류 / 공개 조건 미충족"),
            @ApiResponse(responseCode = "401", description = "인증 실패"),
            @ApiResponse(responseCode = "500", description = "서버 에러")
    })
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> create(
            HttpServletRequest request,
            @Parameter(description = "강의 제목. 필수")
            @RequestParam(value = "name", required = false) String name,
            @Parameter(description = "카테고리. 필수")
            @RequestParam(value = "category", required = false) String category,
            @Parameter(description = "난이도. 필수")
            @RequestParam(value = "level", required = false) String level,
            @Parameter(description = "강의 한 줄 소개. 필수")
            @RequestParam(value = "headline", required = false) String headline,
            @Parameter(description = "강의 소개 본문. 선택")
            @RequestParam(value = "summary", required = false) String summary,
            @Parameter(description = "가격(원). 필수")
            @RequestParam(value = "price", required = false) Integer price,
            @Parameter(description = "상태 open/closed/draft. 필수")
            @RequestParam(value = "status", required = false) String status,
            @Parameter(description = "썸네일 이미지 (png/jpg/jpeg/svg/gif/webp). 필수")
            @RequestParam(value = "thumbnail", required = false) MultipartFile thumbnail) {

        long instructorUserId = jwtUtil.getUid(request);
        // 필수값: 제목·카테고리·난이도·한 줄 소개·가격·상태·썸네일 (summary 만 선택)
        if (isBlank(name) || isBlank(category) || isBlank(level)
                || isBlank(headline) || price == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "제목, 카테고리, 난이도, 한 줄 소개, 가격은 필수입니다.");
        }
        if (!isValidStatus(status)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "상태(status)는 open, closed, draft 중 하나여야 합니다.");
        }
        if (thumbnail == null || thumbnail.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "썸네일 이미지는 필수입니다.");
        }
        LectureSaveRequest req = toRequest(name, category, level, headline, summary, price, status);
        try {
            int lid = lectureService.create(instructorUserId, req, thumbnail);
            return ResponseEntity.ok(Map.of("message", "강의가 저장되었습니다.", "lid", lid));
        } catch (LectureService.UnsupportedFileType
                 | IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    // ---------------- 수정 (기본정보 + 썸네일) ----------------
    @Operation(summary = "강의 수정",
            description = "강의 기본 정보와 썸네일을 수정한다(25번 기본정보 화면). multipart/form-data. "
                    + "user_id 는 JWT 에서 추출한다. 썸네일을 보내면 교체, 안 보내면 기존 유지. "
                    + "name·category·level·headline·price·status 는 필수(summary·thumbnail 은 선택). "
                    + "status 는 open/closed/draft 중 하나이며 요청 값 그대로 저장된다. 본인 강의만.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "수정 성공"),
            @ApiResponse(responseCode = "400", description = "필수 정보 누락 / 썸네일 형식 오류 / 공개 조건 미충족"),
            @ApiResponse(responseCode = "401", description = "인증 실패"),
            @ApiResponse(responseCode = "404", description = "강의 없음 또는 권한 없음"),
            @ApiResponse(responseCode = "500", description = "서버 에러")
    })
    @PutMapping(value = "/{lid}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, String>> update(
            @PathVariable("lid") int lid,
            HttpServletRequest request,
            @Parameter(description = "강의 제목. 필수")
            @RequestParam(value = "name", required = false) String name,
            @Parameter(description = "카테고리. 필수")
            @RequestParam(value = "category", required = false) String category,
            @Parameter(description = "난이도. 필수")
            @RequestParam(value = "level", required = false) String level,
            @Parameter(description = "강의 한 줄 소개. 필수")
            @RequestParam(value = "headline", required = false) String headline,
            @Parameter(description = "강의 소개 본문. 선택")
            @RequestParam(value = "summary", required = false) String summary,
            @Parameter(description = "가격(원). 필수")
            @RequestParam(value = "price", required = false) Integer price,
            @Parameter(description = "상태 open/closed/draft. 필수")
            @RequestParam(value = "status", required = false) String status,
            @Parameter(description = "썸네일 이미지. 보내면 교체, 안 보내면 기존 유지")
            @RequestParam(value = "thumbnail", required = false) MultipartFile thumbnail) {

        long instructorUserId = jwtUtil.getUid(request);
        // 필수값: 제목·카테고리·난이도·한 줄 소개·가격·상태 (summary·thumbnail 은 선택)
        if (isBlank(name) || isBlank(category) || isBlank(level)
                || isBlank(headline) || price == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "제목, 카테고리, 난이도, 한 줄 소개, 가격은 필수입니다.");
        }
        if (!isValidStatus(status)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "상태(status)는 open, closed, draft 중 하나여야 합니다.");
        }
        LectureSaveRequest req = toRequest(name, category, level, headline, summary, price, status);
        try {
            lectureService.update(lid, instructorUserId, req, thumbnail);
        } catch (LectureService.NotFoundOrForbidden e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "강의 정보 없음");
        } catch (LectureService.UnsupportedFileType
                 | IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        return ResponseEntity.ok(Map.of("message", "강의가 수정되었습니다."));
    }

    // ---------------- 자료 업로드 (영상·자료 슬롯 2개) ----------------
    @Operation(summary = "강의 자료 업로드",
            description = "26번 자료업로드 화면. 영상(mp4)·자료(pdf/zip)를 슬롯별로 올린다(최대 2개). "
                    + "user_id 는 JWT 에서 추출한다. 한쪽만 보내도 된다(보낸 칸만 교체). 둘 다 비면 400. "
                    + "썸네일은 여기가 아니라 기본정보 등록/수정에서 올린다. 본인 강의만.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "업로드 성공"),
            @ApiResponse(responseCode = "400", description = "업로드할 파일 없음 / 슬롯별 지원하지 않는 형식"),
            @ApiResponse(responseCode = "401", description = "인증 실패"),
            @ApiResponse(responseCode = "404", description = "강의 없음 또는 권한 없음"),
            @ApiResponse(responseCode = "500", description = "서버 에러")
    })
    @PutMapping(value = "/{lid}/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, String>> uploadFiles(
            @PathVariable("lid") int lid,
            HttpServletRequest request,
            @Parameter(description = "강의 영상 (mp4). 선택")
            @RequestParam(value = "video", required = false) MultipartFile video,
            @Parameter(description = "강의 자료 (pdf/zip). 선택")
            @RequestParam(value = "material", required = false) MultipartFile material) {
        long instructorUserId = jwtUtil.getUid(request);
        try {
            lectureService.uploadFiles(lid, instructorUserId, video, material);
        } catch (LectureService.NotFoundOrForbidden e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "강의 정보 없음");
        } catch (LectureService.UnsupportedFileType | IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        return ResponseEntity.ok(Map.of("message", "파일이 업로드되었습니다."));
    }

    /** 폼 파라미터를 LectureSaveRequest 로 모은다. */
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

    /** status 는 open/closed/draft 중 하나여야 한다 (대소문자 무시). */
    private boolean isValidStatus(String s) {
        if (s == null) return false;
        String v = s.trim().toLowerCase();
        return v.equals("open") || v.equals("closed") || v.equals("draft");
    }
}