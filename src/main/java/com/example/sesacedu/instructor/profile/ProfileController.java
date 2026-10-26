package com.example.sesacedu.instructor.profile;

import com.example.sesacedu.global.config.JwtUtil;
import com.example.sesacedu.instructor.profile.dto.ProfileDto;
import io.jsonwebtoken.Claims;
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

/*
 * ===================== [취약 진단용 - profile] JWT 인증은 원본 유지 =====================
 *  (V-VALID/A04) 입력검증 제거 : 필수값(강사명/은행/계좌) 검사 삭제
 *  (연계) 리포지토리 SQLi(A03), 미살균 저장/반환 → XSS(A03)
 * ====================================================================================
 */
@Tag(name = "강사 정보 관리")
@RestController
@RequestMapping("/instructor/profile")
public class ProfileController {

    private final ProfileService profileService;
    private final JwtUtil jwtUtil;

    public ProfileController(ProfileService profileService, JwtUtil jwtUtil) {
        this.profileService = profileService;
        this.jwtUtil = jwtUtil;
    }

    /** Authorization 헤더의 JWT 에서 강사 user_id 를 꺼낸다 (승인 강사 권한 확인 포함). JWT 유지. */
    private long instructorId(HttpServletRequest request) {
        Claims claims = jwtUtil.parse(request);        // 401
        jwtUtil.requireRole(claims, "INSTRUCTOR");     // 403
        return jwtUtil.getUid(claims);
    }

    @Operation(summary = "강사 정보 조회", description = "본인의 공개 프로필과 정산 계좌 정보를 조회한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "401", description = "인증 실패(토큰 없음/만료)"),
            @ApiResponse(responseCode = "403", description = "승인된 강사가 아님"),
            @ApiResponse(responseCode = "404", description = "강사 정보 없음"),
            @ApiResponse(responseCode = "500", description = "서버 에러")
    })
    @GetMapping
    public ResponseEntity<ProfileDto> getProfile(HttpServletRequest request) {
        try {
            return ResponseEntity.ok(profileService.getProfile(instructorId(request)));
        } catch (ProfileService.InstructorNotFound e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "강사 정보 없음");
        }
    }

    @Operation(summary = "강사 정보 수정", description = "[취약] 입력검증 없이 저장.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "수정 성공"),
            @ApiResponse(responseCode = "401", description = "인증 실패(토큰 없음/만료)"),
            @ApiResponse(responseCode = "403", description = "승인된 강사가 아님"),
            @ApiResponse(responseCode = "404", description = "강사 정보 없음"),
            @ApiResponse(responseCode = "500", description = "서버 에러")
    })
    @PutMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, String>> updateProfile(
            @RequestParam(value = "iname", required = false) String iname,
            @RequestParam(value = "bio", required = false) String bio,
            @RequestParam(value = "career", required = false) String career,
            @RequestParam(value = "field", required = false) String field,
            @RequestParam(value = "link", required = false) String link,
            @RequestParam(value = "bank", required = false) String bank,
            @RequestParam(value = "account", required = false) String account,
            @RequestParam(value = "file", required = false) MultipartFile file,
            HttpServletRequest request) {

        long userId = instructorId(request);

        // [필수값 검증 복원] 공개 강사명·정산 은행·계좌번호 필수
        if (isBlank(iname) || isBlank(bank) || isBlank(account)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "공개 강사명, 정산 은행, 계좌번호는 필수입니다.");
        }

        ProfileDto req = new ProfileDto();
        req.setIname(iname);
        req.setBio(bio);
        req.setCareer(career);
        req.setField(field);
        req.setLink(link);
        req.setBank(bank);
        req.setAccount(account);

        try {
            profileService.updateProfile(userId, req, file);
        } catch (ProfileService.InstructorNotFound e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "강사 정보 없음");
        }
        return ResponseEntity.ok(Map.of("message", "강사 정보가 저장되었습니다."));
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}