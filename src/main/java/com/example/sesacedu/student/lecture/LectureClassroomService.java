package com.example.sesacedu.student.lecture;

import com.example.sesacedu.global.auth.CurrentUser;
import com.example.sesacedu.global.exception.ApiException;
import com.example.sesacedu.global.storage.FileStorage;
import com.example.sesacedu.student.lecture.dto.AssignmentResponse;
import com.example.sesacedu.student.lecture.dto.AssignmentSubmitRequest;
import com.example.sesacedu.student.lecture.dto.CompleteResponse;
import com.example.sesacedu.student.lecture.dto.CreatedResponse;
import com.example.sesacedu.student.lecture.dto.LearnResponse;
import com.example.sesacedu.student.lecture.dto.MaterialResponse;
import com.example.sesacedu.student.lecture.dto.PositionSaveRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 수강 화면(영상 · 자료 · 과제 · 이어보기 · 수료) 처리.
 */
@Service
public class LectureClassroomService {

    /** 영상을 끝까지 봐야 수료다. */
    static final double COMPLETE_RATIO = 1.0;

    private final LectureRepository lectureRepository;
    private final LectureClassroomRepository classroomRepository;
    private final LectureFileStorage storage;
    private final FileStorage fileStorage;

    /** 과제 첨부 허용 확장자와 크기 (10MB) */
    private static final Set<String> WORK_EXT = Set.of("pdf", "zip", "docx", "hwp", "pptx", "png", "jpg", "jpeg", "txt");
    private static final long WORK_MAX_BYTES = 10L * 1024 * 1024;

    public LectureClassroomService(LectureRepository lectureRepository,
                                   LectureClassroomRepository classroomRepository,
                                   LectureFileStorage storage,
                                   FileStorage fileStorage) {
        this.lectureRepository = lectureRepository;
        this.classroomRepository = classroomRepository;
        this.storage = storage;
        this.fileStorage = fileStorage;
    }

    /**
     * POST /lectures/{Lid}/assignments/file — 과제 첨부 파일 업로드.
     * 수강자만. {저장소}/assignment/{Lid}/ 에 저장하고, 제출·수정 API 에 넣을 work·workFilename 을 돌려준다.
     */
    public Map<String, String> uploadWork(int lid, CurrentUser currentUser, MultipartFile file) {
        Enrolled enrolled = requireEnrolled(lid, currentUser);
        if (enrolled.lecture.getAssignmentTitle() == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "ASSIGNMENT_NOT_FOUND", "등록된 과제가 없습니다.");
        }
        if (file == null || file.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "첨부할 파일을 선택해 주세요.");
        }
        if (file.getSize() > WORK_MAX_BYTES) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE", "과제 파일은 10MB까지 올릴 수 있습니다.");
        }
        String original = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        int dot = original.lastIndexOf('.');
        String ext = dot < 0 ? "" : original.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!WORK_EXT.contains(ext)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "UNSUPPORTED_FILE_TYPE",
                    "과제 파일은 " + String.join(", ", WORK_EXT) + " 형식만 올릴 수 있습니다.");
        }
        String work = fileStorage.store(file, "assignment/" + lid);
        Map<String, String> result = new LinkedHashMap<>();
        result.put("work", work);
        String stored = work.substring(work.lastIndexOf('/') + 1);
        result.put("workFilename", stored.substring(stored.indexOf('_') + 1));   // 앞의 UUID_ 를 뗀 원본 이름
        return result;
    }

    /** GET /lectures/{Lid}/learn — 영상 재생 화면. */
    public LearnResponse learn(int lid, CurrentUser currentUser) {
        Enrolled enrolled = requireEnrolled(lid, currentUser);
        ClassroomRow lecture = enrolled.lecture;

        return new LearnResponse(
                lecture.getLid(),
                lecture.getName(),
                lecture.getVideo() == null ? null : "/lectures/" + lid + "/learn/video",
                lecture.getDuration(),
                enrolled.progress.getPosition(),
                enrolled.progress.isDone(),
                "/lectures/" + lid + "/material",
                "/lectures/" + lid + "/assignment",
                classroomRepository.findChapters(lid));
    }

    /** GET /lectures/{Lid}/material — 강의 자료 안내. */
    public MaterialResponse material(int lid, CurrentUser currentUser) {
        ClassroomRow lecture = requireEnrolled(lid, currentUser).lecture;

        if (lecture.getMaterial() == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "MATERIAL_NOT_FOUND", "등록된 강의 자료가 없습니다.");
        }

        // 원본 파일명이 비어 있으면 강의명으로 대신한다.
        String filename = lecture.getMaterialFilename() == null
                ? lecture.getName()
                : lecture.getMaterialFilename();

        return new MaterialResponse(lid, filename, "/lectures/" + lid + "/material/download");
    }

    /** PUT /lectures/{Lid}/learn/position — 이어보기 위치 저장. 재생 중에 주기적으로 불린다. */
    public void savePosition(int lid, CurrentUser currentUser, PositionSaveRequest request) {
        requireEnrolled(lid, currentUser);

        if (request == null || request.getPosition() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "재생 위치를 보내 주세요.");
        }

        classroomRepository.updatePosition(lid, currentUser.getUserId(), request.getPosition());
    }

    /** POST /lectures/{Lid}/complete — 수료 처리. 저장된 위치가 영상 끝이면 수료다. */
    public CompleteResponse complete(int lid, CurrentUser currentUser) {
        Enrolled enrolled = requireEnrolled(lid, currentUser);
        Integer duration = enrolled.lecture.getDuration();
        Integer position = enrolled.progress.getPosition();

        if (enrolled.progress.isDone()) {
            throw new ApiException(HttpStatus.CONFLICT, "ALREADY_COMPLETED", "이미 수료한 강의입니다.");
        }

        int required = duration == null ? 0 : (int) Math.ceil(duration * COMPLETE_RATIO);
        int watched = position == null ? 0 : position;
        if (watched < required) {
            throw new ApiException(HttpStatus.CONFLICT, "NOT_WATCHED_ENOUGH",
                    "영상을 끝까지 보셔야 수료로 처리됩니다.");
        }

        if (classroomRepository.markDone(lid, currentUser.getUserId()) == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "ALREADY_COMPLETED", "이미 수료한 강의입니다.");
        }

        return new CompleteResponse(lid, true, position, duration, required);
    }

    /** GET /lectures/{Lid}/learn/video — 영상 파일. */
    public StoredFile videoFile(int lid, CurrentUser currentUser) {
        ClassroomRow lecture = requireEnrolled(lid, currentUser).lecture;

        Path path = storage.resolve(lecture.getVideo(), "VIDEO_NOT_FOUND", "등록된 강의 영상이 없습니다.");
        String name = lecture.getVideoFilename() == null ? lecture.getName() : lecture.getVideoFilename();
        return new StoredFile(path, name);
    }

    /** GET /lectures/{Lid}/material/download — 강의 자료 파일. */
    public StoredFile materialFile(int lid, CurrentUser currentUser) {
        ClassroomRow lecture = requireEnrolled(lid, currentUser).lecture;

        Path path = storage.resolve(lecture.getMaterial(), "MATERIAL_NOT_FOUND", "등록된 강의 자료가 없습니다.");
        String name = lecture.getMaterialFilename() == null ? lecture.getName() : lecture.getMaterialFilename();
        return new StoredFile(path, name);
    }

    /** GET /lectures/{Lid}/assignment — 과제 안내와 내 제출물. */
    public AssignmentResponse assignment(int lid, CurrentUser currentUser) {
        Enrolled enrolled = requireEnrolled(lid, currentUser);
        ClassroomRow lecture = enrolled.lecture;

        if (lecture.getAssignmentTitle() == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "ASSIGNMENT_NOT_FOUND", "등록된 과제가 없습니다.");
        }

        AssignmentResponse.SubmissionResponse submission = classroomRepository
                .findMySubmission(lid, currentUser.getUserId())
                .map(row -> toSubmissionResponse(lid, row))
                .orElse(null);

        return new AssignmentResponse(
                lid,
                lecture.getAssignmentTitle(),
                lecture.getAssignmentContent(),
                lecture.getAssignmentFileType(),
                submission);
    }

    /** POST /lectures/{Lid}/assignments — 과제 제출. 강의당 1인 1건이다. */
    public CreatedResponse submit(int lid, CurrentUser currentUser, AssignmentSubmitRequest request) {
        Enrolled enrolled = requireEnrolled(lid, currentUser);
        if (enrolled.lecture.getAssignmentTitle() == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "ASSIGNMENT_NOT_FOUND", "등록된 과제가 없습니다.");
        }
        validate(request);

        if (classroomRepository.findMySubmission(lid, currentUser.getUserId()).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "ALREADY_SUBMITTED",
                    "이미 제출했습니다. 수정으로 바꿔 주세요.");
        }

        int aid = classroomRepository.insertSubmission(lid, currentUser.getUserId(),
                request.getWork(), request.getWorkFilename(), request.getNote());
        return new CreatedResponse(aid);
    }

    /** GET /lectures/{Lid}/assignments/{aid} — 제출물 한 건. */
    public AssignmentResponse.SubmissionResponse submission(int lid, int aid, CurrentUser currentUser) {
        requireEnrolled(lid, currentUser);
        return toSubmissionResponse(lid, findSubmission(aid));
    }

    /** PUT /lectures/{Lid}/assignments/{aid} — 제출물 수정. 채점이 끝나면 수정할 수 없다. */
    public AssignmentResponse.SubmissionResponse modify(int lid, int aid, CurrentUser currentUser,
                                                       AssignmentSubmitRequest request) {
        requireEnrolled(lid, currentUser);
        validate(request);

        if (findSubmission(aid).getScore() != null) {
            throw new ApiException(HttpStatus.CONFLICT, "ALREADY_SCORED", "채점이 끝난 제출물은 수정할 수 없습니다.");
        }

        classroomRepository.updateSubmission(aid, request.getWork(), request.getWorkFilename(), request.getNote());
        return toSubmissionResponse(lid, findSubmission(aid));
    }

    /** DELETE /lectures/{Lid}/assignments/{aid} — 제출물 삭제. 채점이 끝나면 삭제할 수 없다. */
    public void remove(int lid, int aid, CurrentUser currentUser) {
        requireEnrolled(lid, currentUser);

        if (findSubmission(aid).getScore() != null) {
            throw new ApiException(HttpStatus.CONFLICT, "ALREADY_SCORED", "채점이 끝난 제출물은 삭제할 수 없습니다.");
        }

        classroomRepository.deleteSubmission(aid);
    }

    /** GET /lectures/{Lid}/assignments/{aid}/file — 제출한 파일 다운로드. */
    public StoredFile submissionFile(int lid, int aid, CurrentUser currentUser) {
        requireEnrolled(lid, currentUser);

        String[] file = classroomRepository.findWorkFile(aid)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND, "SUBMISSION_NOT_FOUND", "제출물을 찾을 수 없습니다."));

        Path path = storage.resolve(file[0], "SUBMISSION_FILE_NOT_FOUND", "제출한 파일이 없습니다.");
        return new StoredFile(path, file[1] == null ? "submission" : file[1]);
    }

    private SubmissionRow findSubmission(int aid) {
        return classroomRepository.findSubmissionById(aid)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND, "SUBMISSION_NOT_FOUND", "제출물을 찾을 수 없습니다."));
    }

    /** 파일과 내용 중 하나는 있어야 한다. 파일 없이 낸 경우 원본 파일명도 비운다. */
    private void validate(AssignmentSubmitRequest request) {
        if (request == null || (isBlank(request.getWork()) && isBlank(request.getNote()))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "파일을 첨부하거나 내용을 입력해 주세요.");
        }
        if (isBlank(request.getWork())) {
            request.setWork(null);
            request.setWorkFilename(null);
        }
        if (length(request.getWork()) > 500 || length(request.getWorkFilename()) > 255) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "파일 정보가 너무 깁니다.");
        }
        if (length(request.getNote()) > 3000) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "내용은 3000자까지 입력할 수 있습니다.");
        }
    }

    private AssignmentResponse.SubmissionResponse toSubmissionResponse(int lid, SubmissionRow row) {
        String fileUrl = row.getWorkFilename() == null
                ? null
                : "/lectures/" + lid + "/assignments/" + row.getAid() + "/file";
        return new AssignmentResponse.SubmissionResponse(
                row.getAid(), row.getWorkFilename(), fileUrl, row.getNote(), row.getSubmitted(),
                row.getScore(), row.getFeedback(), row.getFeedbackAt());
    }

    private static boolean isBlank(String v) {
        return v == null || v.trim().isEmpty();
    }

    private static int length(String v) {
        return v == null ? 0 : v.length();
    }

    /**
     * 수강 화면 공통 확인. 로그인 → 강의 존재 → 수강 여부 순서다.
     * 수강 여부는 enrollments(user_id, Lid) 행으로 본다.
     */
    private Enrolled requireEnrolled(int lid, CurrentUser currentUser) {
        if (currentUser == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "로그인이 필요합니다.");
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

    private static final class Enrolled {
        private final ClassroomRow lecture;
        private final EnrollmentProgress progress;

        private Enrolled(ClassroomRow lecture, EnrollmentProgress progress) {
            this.lecture = lecture;
            this.progress = progress;
        }
    }
}
