package com.example.sesacedu.instructor.profile;

import com.example.sesacedu.instructor.profile.dto.ProfileDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@Tag(name = "강사 정보 관리")
@RestController
@RequestMapping("/instructor/profile")
public class ProfileController {

    private final ProfileService profileService;

    public ProfileController(ProfileService profileService) {
        this.profileService = profileService;
    }

    // ---------------- 조회 ----------------
    @Operation(summary = "강사 정보 조회",
            description = "본인의 공개 프로필과 정산 계좌 정보를 조회한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "403", description = "승인된 강사가 아님 (신청 대기 등)"),
            @ApiResponse(responseCode = "404", description = "강사 정보 없음"),
            @ApiResponse(responseCode = "500", description = "서버 에러")
    })
    @GetMapping
    public ResponseEntity<ProfileDto> getProfile(
            @Parameter(description = "임시 강사 사용자 번호 (추후 JWT 로 대체)")
            @RequestParam("uid") int userId) {
        try {
            return ResponseEntity.ok(profileService.getProfile(userId));
        } catch (ProfileService.NotInstructor e) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "승인된 강사만 접근할 수 있습니다.");
        } catch (ProfileService.InstructorNotFound e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "강사 정보 없음");
        }
    }

    // ---------------- 수정 ----------------
    @Operation(summary = "강사 정보 수정",
            description = "공개 프로필과 정산 계좌 정보를 수정한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "수정 성공"),
            @ApiResponse(responseCode = "400", description = "필수 정보 누락"),
            @ApiResponse(responseCode = "403", description = "승인된 강사가 아님 (신청 대기 등)"),
            @ApiResponse(responseCode = "404", description = "강사 정보 없음"),
            @ApiResponse(responseCode = "500", description = "서버 에러")
    })
    @PutMapping
    public ResponseEntity<Map<String, String>> updateProfile(
            @Parameter(description = "임시 강사 사용자 번호 (추후 JWT 로 대체)")
            @RequestParam("uid") int userId,
            @RequestBody ProfileDto req) {

        // 화면 기준 필수값: 공개 강사명, 정산 은행, 계좌번호
        if (isBlank(req.getIname()) || isBlank(req.getBank()) || isBlank(req.getAccount())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "공개 강사명, 정산 은행, 계좌번호는 필수입니다.");
        }
        try {
            profileService.updateProfile(userId, req);
        } catch (ProfileService.NotInstructor e) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "승인된 강사만 접근할 수 있습니다.");
        } catch (ProfileService.InstructorNotFound e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "강사 정보 없음");
        }
        return ResponseEntity.ok(Map.of("message", "강사 정보가 저장되었습니다."));
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}