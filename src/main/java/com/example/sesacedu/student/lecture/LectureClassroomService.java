package com.example.sesacedu.student.lecture;

import com.example.sesacedu.global.auth.CurrentUser;
import com.example.sesacedu.global.exception.ApiException;
import com.example.sesacedu.student.lecture.dto.AssignmentResponse;
import com.example.sesacedu.student.lecture.dto.AssignmentSubmitRequest;
import com.example.sesacedu.student.lecture.dto.CreatedResponse;
import com.example.sesacedu.student.lecture.dto.LearnResponse;
import com.example.sesacedu.student.lecture.dto.MaterialResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 수강 화면(영상 · 자료 · 과제)의 판단을 담당한다.
 *
 * 세 API 가 전부 같은 관문을 지난다 — 로그인했는가, 그리고 이 강의를 수강 중인가.
 * 그 검사를 requireEnrolled 한 곳에 모아 뒀다. API 마다 따로 쓰면 하나를
 * 빠뜨렸을 때 그 자리만 권한 없이 열린다.
 */
@Service
public class LectureClassroomService {

    private final LectureRepository lectureRepository;
    private final LectureClassroomRepository classroomRepository;

    public LectureClassroomService(LectureRepository lectureRepository,
                                   LectureClassroomRepository classroomRepository) {
        this.lectureRepository = lectureRepository;
        this.classroomRepository = classroomRepository;
    }

    /** GET /lectures/{Lid}/learn — 영상 재생 화면. */
    public LearnResponse learn(int lid, CurrentUser currentUser) {
        Enrolled enrolled = requireEnrolled(lid, currentUser);
        ClassroomRow lecture = enrolled.lecture;

        return new LearnResponse(
                lecture.getLid(),
                lecture.getName(),
                // 영상 파일이 등록돼 있을 때만 재생 주소를 준다. 경로 자체는 넘기지 않는다.
                lecture.getVideo() == null ? null : "/lectures/" + lid + "/learn/video",
                lecture.getDuration(),
                enrolled.progress.getPosition(),
                enrolled.progress.isDone(),
                "/lectures/" + lid + "/material",
                "/lectures/" + lid + "/assignment");
    }

    /** GET /lectures/{Lid}/material — 강의 자료 안내. */
    public MaterialResponse material(int lid, CurrentUser currentUser) {
        ClassroomRow lecture = requireEnrolled(lid, currentUser).lecture;

        // 수강 권한은 있지만 강사가 자료를 아직 안 올린 경우다. 권한 문제와 구분해 404 로 답한다.
        if (lecture.getMaterial() == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "MATERIAL_NOT_FOUND", "등록된 강의 자료가 없습니다.");
        }

        // 원본 파일명이 비어 있으면 화면에 보여 줄 이름이 없다. 그때는 강의명으로 대신한다.
        String filename = lecture.getMaterialFilename() == null
                ? lecture.getName()
                : lecture.getMaterialFilename();

        return new MaterialResponse(lid, filename, "/lectures/" + lid + "/material/download");
    }

    /** GET /lectures/{Lid}/assignment — 과제 안내와 내 제출물. */
    public AssignmentResponse assignment(int lid, CurrentUser currentUser) {
        Enrolled enrolled = requireEnrolled(lid, currentUser);
        ClassroomRow lecture = enrolled.lecture;

        if (lecture.getAssignmentTitle() == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "ASSIGNMENT_NOT_FOUND", "등록된 과제가 없습니다.");
        }

        // 내 user_id 로만 조회한다. 남의 제출물은 이 API 로 나올 수 없다.
        AssignmentResponse.SubmissionResponse submission = classroomRepository
                .findMySubmission(lid, currentUser.getUserId())
                .map(row -> new AssignmentResponse.SubmissionResponse(
                        row.getAid(),
                        row.getWorkFilename(),
                        row.getNote(),
                        row.getSubmitted(),
                        row.getScore(),
                        row.getFeedback(),
                        row.getFeedbackAt()))
                .orElse(null);

        return new AssignmentResponse(
                lid,
                lecture.getAssignmentTitle(),
                lecture.getAssignmentContent(),
                lecture.getAssignmentFileType(),
                submission);
    }

    /** POST /lectures/{Lid}/assignments — 과제 제출. */
    public CreatedResponse submit(int lid, CurrentUser currentUser, AssignmentSubmitRequest request) {
        Enrolled enrolled = requireEnrolled(lid, currentUser);
        requireAssignmentOpen(enrolled.lecture);
        validate(request);

        // 과제는 강의당 1개, 제출은 사용자당 1개다. 이미 냈으면 수정으로 가야 한다.
        // 그냥 INSERT 하면 UNIQUE (Lid, user_id) 에 걸려 DB 오류가 그대로 올라온다.
        if (classroomRepository.findMySubmission(lid, currentUser.getUserId()).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "ALREADY_SUBMITTED",
                    "이미 제출했습니다. 수정으로 바꿔 주세요.");
        }

        int aid = classroomRepository.insertSubmission(lid, currentUser.getUserId(),
                request.getWork(), request.getWorkFilename(), request.getNote());
        return new CreatedResponse(aid);
    }

    /** GET /lectures/{Lid}/assignments/{aid} — 제출물 한 건. 내 것만 보인다. */
    public AssignmentResponse.SubmissionResponse submission(int lid, int aid, CurrentUser currentUser) {
        requireEnrolled(lid, currentUser);
        if (aid < 1) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "제출 번호가 올바르지 않습니다.");
        }

        SubmissionRow row = classroomRepository
                .findMySubmissionById(lid, currentUser.getUserId(), aid)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND, "SUBMISSION_NOT_FOUND", "제출물을 찾을 수 없습니다."));

        return toSubmissionResponse(row);
    }

    /** PUT /lectures/{Lid}/assignments/{aid} — 제출물 수정. */
    public AssignmentResponse.SubmissionResponse modify(int lid, int aid, CurrentUser currentUser,
                                                       AssignmentSubmitRequest request) {
        requireEnrolled(lid, currentUser);
        validate(request);
        if (aid < 1) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "제출 번호가 올바르지 않습니다.");
        }

        // 채점이 끝난 뒤에는 바꿀 수 없다. 점수를 받은 다음 내용을 갈아 끼우면
        // 그 점수가 무엇에 대한 것인지 알 수 없게 된다.
        SubmissionRow current = classroomRepository
                .findMySubmissionById(lid, currentUser.getUserId(), aid)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND, "SUBMISSION_NOT_FOUND", "제출물을 찾을 수 없습니다."));
        if (current.getScore() != null) {
            throw new ApiException(HttpStatus.CONFLICT, "ALREADY_SCORED",
                    "채점이 끝난 제출물은 수정할 수 없습니다.");
        }

        classroomRepository.updateSubmission(lid, currentUser.getUserId(), aid,
                request.getWork(), request.getWorkFilename(), request.getNote());

        return toSubmissionResponse(classroomRepository
                .findMySubmissionById(lid, currentUser.getUserId(), aid)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND, "SUBMISSION_NOT_FOUND", "제출물을 찾을 수 없습니다.")));
    }

    /** DELETE /lectures/{Lid}/assignments/{aid} — 제출물 삭제. */
    public void remove(int lid, int aid, CurrentUser currentUser) {
        requireEnrolled(lid, currentUser);
        if (aid < 1) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "제출 번호가 올바르지 않습니다.");
        }

        SubmissionRow current = classroomRepository
                .findMySubmissionById(lid, currentUser.getUserId(), aid)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND, "SUBMISSION_NOT_FOUND", "제출물을 찾을 수 없습니다."));
        if (current.getScore() != null) {
            throw new ApiException(HttpStatus.CONFLICT, "ALREADY_SCORED",
                    "채점이 끝난 제출물은 삭제할 수 없습니다.");
        }

        // 지워진 행이 0 이면 내 것이 아니라는 뜻이다. 위에서 이미 걸러지지만
        // 그 사이에 다른 요청이 지웠을 수 있어 한 번 더 본다.
        if (classroomRepository.deleteSubmission(lid, currentUser.getUserId(), aid) == 0) {
            throw new ApiException(HttpStatus.NOT_FOUND, "SUBMISSION_NOT_FOUND", "제출물을 찾을 수 없습니다.");
        }
    }

    private void requireAssignmentOpen(ClassroomRow lecture) {
        if (lecture.getAssignmentTitle() == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "ASSIGNMENT_NOT_FOUND", "등록된 과제가 없습니다.");
        }
    }

    /**
     * 제출 내용 검사.
     *
     * 길이만 본다. HTML 태그를 지우지 않는다. 지우면 사용자가 쓴 원문이 사라지고,
     * 무엇을 지울지에 대한 규칙을 서버와 화면이 따로 갖게 된다. 이스케이프는
     * 출력하는 쪽의 일이다. 길이 제한은 DB 컬럼 크기를 넘겨 DB 오류가 그대로
     * 올라오는 것을 막기 위한 것이다.
     */
    private void validate(AssignmentSubmitRequest request) {
        if (request == null || isBlank(request.getWork())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "제출 파일이 필요합니다.");
        }
        if (request.getWork().length() > 500 || length(request.getWorkFilename()) > 255) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "파일 정보가 너무 깁니다.");
        }
        if (length(request.getNote()) > 1000) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "메모는 1000자까지 입력할 수 있습니다.");
        }
    }

    private AssignmentResponse.SubmissionResponse toSubmissionResponse(SubmissionRow row) {
        return new AssignmentResponse.SubmissionResponse(
                row.getAid(), row.getWorkFilename(), row.getNote(), row.getSubmitted(),
                row.getScore(), row.getFeedback(), row.getFeedbackAt());
    }

    private static boolean isBlank(String v) {
        return v == null || v.trim().isEmpty();
    }

    private static int length(String v) {
        return v == null ? 0 : v.length();
    }

    /**
     * 수강 권한 관문.    /**
     * 수강 권한 관문. 세 API 가 모두 여기를 지난다.
     *
     * 검사 순서에 뜻이 있다.
     *   1. 로그인 안 함        → 401. 강의가 있는지조차 알려 주지 않는다
     *   2. 강의 없음·draft     → 404
     *   3. 수강 행 없음        → 403. 있는 강의지만 당신은 못 본다
     *
     * 2번을 1번보다 먼저 하면 비로그인 사용자가 번호만 바꿔 가며 요청해서
     * 내려간(closed) 강의가 몇 번에 있는지 알아낼 수 있다. closed 는 공개 목록에
     * 나오지 않으므로 그것만으로도 노출이다.
     *
     * 수강 여부는 users.auth 가 아니라 enrollments(user_id, Lid) 로 본다.
     * auth 는 계정에 하나뿐인 값이라 그걸로 판정하면 사지 않은 강의까지 열린다.
     */
    private Enrolled requireEnrolled(int lid, CurrentUser currentUser) {
        if (currentUser == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "로그인이 필요합니다.");
        }
        if (lid < 1) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "강의 번호가 올바르지 않습니다.");
        }

        ClassroomRow lecture = classroomRepository.findForEnrolled(lid)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND, "LECTURE_NOT_FOUND", "강의를 찾을 수 없습니다."));

        EnrollmentProgress progress = lectureRepository
                .findEnrollment(currentUser.getUserId(), lid)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.FORBIDDEN, "NOT_ENROLLED", "수강 중인 강의가 아닙니다."));

        return new Enrolled(lecture, progress);
    }

    /** 관문을 통과한 뒤 세 API 가 같이 쓰는 값 묶음. */
    private static final class Enrolled {
        private final ClassroomRow lecture;
        private final EnrollmentProgress progress;

        private Enrolled(ClassroomRow lecture, EnrollmentProgress progress) {
            this.lecture = lecture;
            this.progress = progress;
        }
    }
}
