package com.example.sesacedu.instructor.apply;

import com.example.sesacedu.global.storage.FileStorage;
import com.example.sesacedu.instructor.apply.dto.ApplyStatusResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * 강사 신청 기능 로직.
 *   - getStatus : 신청 상태 조회 (auth 기반)
 *   - apply     : 강사 신청 (파일 저장 + instructors 행 추가 + auth 변경)
 * user_id 는 컨트롤러가 JWT 에서 추출해 long 으로 넘겨준다.
 */
@Service
public class InstructorApplyService {

    private static final int AUTH_NORMAL = 0;            // 일반
    private static final int AUTH_STUDENT = 1;           // 수강생
    private static final int AUTH_INSTRUCTOR = 2;        // 강사(승인 완료)
    private static final int AUTH_ADMIN = 3;             // 관리자
    private static final int AUTH_APPLY_PENDING = 4;     // 강사 신청 대기

    /** 경력 파일 크기 한도. 과제 첨부와 같은 값으로 맞췄다. */
    private static final long CAREER_MAX_BYTES = 10L * 1024 * 1024;

    private final InstructorApplyRepository applyRepository;
    private final FileStorage fileStorage;

    public InstructorApplyService(InstructorApplyRepository applyRepository,
                                  FileStorage fileStorage) {
        this.applyRepository = applyRepository;
        this.fileStorage = fileStorage;
    }

    /** 신청 상태 조회 */
    public ApplyStatusResponse getStatus(long userId) {
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

    /**
     * 강사 신청.
     * 파일 저장 후 instructors 행 추가 + users.auth 를 신청 대기(4)로 변경한다.
     * instructors 추가와 auth 변경은 한 트랜잭션으로 묶는다.
     *
     * @throws UserNotFoundException     사용자 없음 (404)
     * @throws AlreadyAppliedException   이미 신청/강사/관리자라 신청 불가 (409)
     */
    @Transactional
    public void apply(long userId, String introduce, String subject, MultipartFile career) {
        Integer auth = applyRepository.findAuthByUserId(userId);
        if (auth == null) {
            throw new UserNotFoundException();
        }
        // 이미 신청 대기(4) / 강사(2) / 관리자(3) 면 신청 불가
        if (auth == AUTH_APPLY_PENDING || auth == AUTH_INSTRUCTOR || auth == AUTH_ADMIN) {
            throw new AlreadyAppliedException();
        }

        // 저장 경로는 보낸 폴더 구조를 살려서 만든다. 그래서 이름에 상위 폴더 표기가 들어오면
        // 저장 폴더 밖을 가리킬 수 있어 먼저 막는다. 크기 한도는 과제 첨부와 같은 10MB 로 둔다.
        String careerName = career.getOriginalFilename() == null ? "" : career.getOriginalFilename();
        if (careerName.contains("../")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "파일 이름에 상위 폴더 표기를 쓸 수 없습니다.");
        }
        if (career.getSize() > CAREER_MAX_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "경력 파일은 10MB까지 올릴 수 있습니다.");
        }

        // 파일 저장 (S3 준비 전엔 임시 구현이 경로만 생성)
        String careerFile = fileStorage.store(career, "career");   // DB career_file 에 저장할 경로
        String fileName = career.getOriginalFilename();            // 원본 파일명

        // iname 은 신청 시점엔 실명(uname)으로 채운다 (추후 강사 정보 관리에서 수정)
        String uname = applyRepository.findUnameByUserId(userId);

        applyRepository.insertApplication(userId, uname, introduce, subject, careerFile, fileName);
        applyRepository.updateAuth(userId, AUTH_APPLY_PENDING);
    }

    /** 사용자 없음 (404) */
    public static class UserNotFoundException extends RuntimeException {
    }

    /** 이미 신청했거나 신청할 수 없는 상태 (409) */
    public static class AlreadyAppliedException extends RuntimeException {
    }
}