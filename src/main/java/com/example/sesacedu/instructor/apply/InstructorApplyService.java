package com.example.sesacedu.instructor.apply;

import com.example.sesacedu.instructor.apply.dto.ApplyStatusResponse;
import org.springframework.stereotype.Service;

/**
 * 강사 신청 상태 조회 로직.
 * 판정 기준은 users.auth 값이다.
 *   auth 4 -> PENDING (승인 대기)
 *   auth 2 -> APPROVED (승인 완료)
 *   그 외   -> NONE (미신청)
 */
@Service
public class InstructorApplyService {

    private static final int AUTH_INSTRUCTOR = 2;        // 강사(승인 완료)
    private static final int AUTH_APPLY_PENDING = 4;     // 강사 신청 대기

    private final InstructorApplyRepository applyRepository;

    public InstructorApplyService(InstructorApplyRepository applyRepository) {
        this.applyRepository = applyRepository;
    }

    /**
     * @return 신청 상태 응답
     * @throws UserNotFoundException 해당 user_id의 사용자가 없을 때 (컨트롤러에서 404로 변환)
     */
    public ApplyStatusResponse getStatus(int userId) {
        Integer auth = applyRepository.findAuthByUserId(userId);
        if (auth == null) {
            throw new UserNotFoundException();
        }

        if (auth == AUTH_APPLY_PENDING) {
            return new ApplyStatusResponse("PENDING", "승인 대기 중입니다.");
        }
        if (auth == AUTH_INSTRUCTOR) {
            return new ApplyStatusResponse("APPROVED", "승인 완료 / 강사센터 이용 가능");
        }
        return new ApplyStatusResponse("NONE", "미신청 / 승인 후 강사센터 이용 가능");
    }

    /** 사용자 없음 (404) */
    public static class UserNotFoundException extends RuntimeException {
    }
}