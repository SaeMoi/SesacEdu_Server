package com.example.sesacedu.instructor.profile;

import com.example.sesacedu.instructor.profile.dto.ProfileDto;
import org.springframework.stereotype.Service;

/**
 * 강사 정보 관리 로직. 본인(instructors 행)을 조회/수정한다.
 * 승인된 강사(auth=2)만 접근할 수 있다. 신청 대기(4)·그 외는 막는다.
 */
@Service
public class ProfileService {

    private static final int AUTH_INSTRUCTOR = 2;   // 승인된 강사만 접근 허용

    private final ProfileRepository profileRepository;

    public ProfileService(ProfileRepository profileRepository) {
        this.profileRepository = profileRepository;
    }

    /** 조회. 강사 아님(승인 대기/일반 등)이면 접근 차단. */
    public ProfileDto getProfile(int userId) {
        checkInstructor(userId);
        ProfileDto p = profileRepository.findByUserId(userId);
        if (p == null) {
            throw new InstructorNotFound();
        }
        return p;
    }

    /** 수정. 강사 아님이면 접근 차단. */
    public void updateProfile(int userId, ProfileDto req) {
        checkInstructor(userId);
        int updated = profileRepository.update(userId, req);
        if (updated == 0) {
            throw new InstructorNotFound();
        }
    }

    /**
     * 승인된 강사(auth=2)인지 확인.
     * 사용자 없음 -> InstructorNotFound(404)
     * 승인 대기(4)·일반(0,1)·관리자(3) -> NotInstructor(403)
     */
    private void checkInstructor(int userId) {
        Integer auth = profileRepository.findAuthByUserId(userId);
        if (auth == null) {
            throw new InstructorNotFound();
        }
        if (auth != AUTH_INSTRUCTOR) {
            throw new NotInstructor();
        }
    }

    /** 강사 정보 없음 (404) */
    public static class InstructorNotFound extends RuntimeException {
    }

    /** 승인된 강사가 아니라 접근 불가 (403) — 신청 대기 포함 */
    public static class NotInstructor extends RuntimeException {
    }
}