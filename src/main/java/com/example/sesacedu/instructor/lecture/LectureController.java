package com.example.sesacedu.instructor.lecture;

import com.example.sesacedu.global.config.JwtUtil;
import com.example.sesacedu.instructor.lecture.dto.LectureDetail;
import com.example.sesacedu.instructor.lecture.dto.LectureListItem;
import com.example.sesacedu.instructor.lecture.dto.LectureSaveRequest;
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
import java.util.List;
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
            description = "본인 강의 목록과 각 강의 수강생 수를 조회한다. user_id 는 JWT 에서 추출한다. "
                    + "status(open/closed/draft)로 공개 상태를 필터링한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "401", description = "인증 실패"),
            @ApiResponse(responseCode = "500", description = "서버 에러")
    })
    @GetMapping
    public ResponseEntity<List<LectureListItem>> getList(
            HttpServletRequest request,
            @Parameter(description = "상태 필터 (open/closed/draft). 생략 시 전체")
            @RequestParam(value = "status", required = false) String status) {
        long instructorUserId = jwtUtil.getUid(request);
        return ResponseEntity.ok(lectureService.getList(instructorUserId, status));
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
            description = "기본 정보와 썸네일을 저장한다(25번 기본정보 화면, \"저장하고 다음\"). "
                    + "multipart/form-data. user_id 는 JWT 에서 추출한다. status 를 생략하면 draft(임시 저장). "
                    + "영상·자료는 다음 화면의 파일 업로드 API 로 올린다. "
                    + "status=open 은 영상·자료가 모두 있어야 통과(등록 단계에선 아직 없으므로 사실상 draft 로 시작).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "등록 성공"),
            @ApiResponse(responseCode = "400", description = "필수 정보 누락 / 썸네일 형식 오류 / 공개 조건 미충족"),
            @ApiResponse(responseCode = "401", description = "인증 실패"),
            @ApiResponse(responseCode = "500", description = "서버 에러")
    })
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> create(
            HttpServletRequest request,
            @ModelAttribute LectureSaveRequest req,
            @Parameter(description = "썸네일 이미지 (png/jpg/jpeg/svg/gif/webp)")
            @RequestParam(value = "thumbnail", required = false) MultipartFile thumbnail) {

        long instructorUserId = jwtUtil.getUid(request);
        if (isBlank(req.getName()) || isBlank(req.getCategory()) || req.getPrice() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "제목, 카테고리, 가격은 필수입니다.");
        }
        try {
            int lid = lectureService.create(instructorUserId, req, thumbnail);
            return ResponseEntity.ok(Map.of("message", "강의가 저장되었습니다.", "lid", lid));
        } catch (LectureService.CannotPublish
                 | LectureService.UnsupportedFileType
                 | IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    // ---------------- 수정 (기본정보 + 썸네일) ----------------
    @Operation(summary = "강의 수정",
            description = "강의 기본 정보와 썸네일을 수정한다(25번 기본정보 화면). multipart/form-data. "
                    + "user_id 는 JWT 에서 추출한다. 썸네일을 보내면 교체, 안 보내면 기존 유지. status 생략 시 기존 공개 상태 유지. "
                    + "status=open 은 영상·자료가 DB에 모두 있어야 통과. 본인 강의만.")
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
            @ModelAttribute LectureSaveRequest req,
            @Parameter(description = "썸네일 이미지. 보내면 교체, 안 보내면 기존 유지")
            @RequestParam(value = "thumbnail", required = false) MultipartFile thumbnail) {

        long instructorUserId = jwtUtil.getUid(request);
        if (isBlank(req.getName()) || isBlank(req.getCategory()) || req.getPrice() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "제목, 카테고리, 가격은 필수입니다.");
        }
        try {
            lectureService.update(lid, instructorUserId, req, thumbnail);
        } catch (LectureService.NotFoundOrForbidden e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "강의 정보 없음");
        } catch (LectureService.CannotPublish
                 | LectureService.UnsupportedFileType
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
    @PostMapping(value = "/{lid}/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
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

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
