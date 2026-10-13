package com.example.sesacedu.instructor.profile;

import com.example.sesacedu.global.storage.FileStorage;
import com.example.sesacedu.instructor.profile.dto.ProfileDto;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * 강사 정보 관리 로직. 본인(instructors 행)을 조회/수정한다.
 * 승인 강사 여부는 컨트롤러의 JWT role("INSTRUCTOR")로 확인 → 여기선 행 존재(404)만 다룬다.
 * 경력 파일은 FileStorage 로 저장(로컬/S3 교체 가능).
 */
@Service
public class ProfileService {

    private static final String CAREER_DIR = "instructor/career";

    private final ProfileRepository profileRepository;
    private final FileStorage fileStorage;

    public ProfileService(ProfileRepository profileRepository, FileStorage fileStorage) {
        this.profileRepository = profileRepository;
        this.fileStorage = fileStorage;
    }

    /** 조회. instructors 행 없으면 404. */
    public ProfileDto getProfile(long userId) {
        ProfileDto p = profileRepository.findByUserId(userId);
        if (p == null) {
            throw new InstructorNotFound();
        }
        return p;
    }

    /**
     * 수정. 텍스트는 항상 갱신, 파일(file)이 있으면 저장 후 career_file·career_filename 갱신.
     * 대상 행 없으면 404.
     */
    public void updateProfile(long userId, ProfileDto req, MultipartFile file) {
        int updated = profileRepository.update(userId, req);
        if (updated == 0) {
            throw new InstructorNotFound();
        }
        if (file != null && !file.isEmpty()) {
            String key = fileStorage.store(file, CAREER_DIR);          // 로컬/S3 저장 → 키
            profileRepository.updateCareerFile(userId, key, file.getOriginalFilename());
        }
    }

    /** 강사 정보 없음 (404) */
    public static class InstructorNotFound extends RuntimeException {
    }
}