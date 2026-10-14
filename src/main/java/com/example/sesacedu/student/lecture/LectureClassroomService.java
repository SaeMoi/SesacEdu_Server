package com.example.sesacedu.student.lecture;

import com.example.sesacedu.global.auth.CurrentUser;
import com.example.sesacedu.global.exception.ApiException;
import com.example.sesacedu.student.lecture.dto.AssignmentResponse;
import com.example.sesacedu.student.lecture.dto.AssignmentSubmitRequest;
import com.example.sesacedu.student.lecture.dto.CompleteResponse;
import com.example.sesacedu.student.lecture.dto.CreatedResponse;
import com.example.sesacedu.student.lecture.dto.LearnResponse;
import com.example.sesacedu.student.lecture.dto.MaterialResponse;
import com.example.sesacedu.student.lecture.dto.PositionSaveRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

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
    private final LectureFileStorage storage;
    private Clock clock = Clock.systemDefaultZone();

    public LectureClassroomService(LectureRepository lectureRepository,
                                   LectureClassroomRepository classroomRepository,
                                   LectureFileStorage storage) {
        this.lectureRepository = lectureRepository;
        this.classroomRepository = classroomRepository;
        this.storage = storage;
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
                "/lectures/" + lid + "/assignment",
                classroomRepository.findChapters(lid));
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

    /**
     * 수료로 인정하는 시청 비율. 영상을 끝까지 봐야 수료다(1.0 = 100%).
     *
     * 이어보기 위치는 영상 길이로 잘라 저장하므로 끝까지 보면 위치가 길이와 같아진다.
     * 값을 상수로 빼 둔 건 기준이 바뀔 때 한 곳만 고치게 하려는 것이다.
     */
    static final double COMPLETE_RATIO = 1.0;

    /**
     * PUT /lectures/{Lid}/learn/position — 이어보기 위치 저장.
     *
     * 재생 중에 주기적으로 불린다. 저장된 위치는 다음 접속 때 learn 응답의
     * position 으로 내려간다.
     *
     * 위치를 영상 길이로 잘라 둔다. 자르지 않으면 길이보다 큰 값이 들어가고,
     * 그 값으로 수료 판정을 하면 영상을 열어 보지 않고도 수료가 된다.
     */
    public void savePosition(int lid, CurrentUser currentUser, PositionSaveRequest request) {
        Enrolled enrolled = requireEnrolled(lid, currentUser);

        if (request == null || request.getPosition() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "재생 위치를 보내 주세요.");
        }
        int position = request.getPosition();
        if (position < 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "재생 위치는 0 이상이어야 합니다.");
        }

        Instant now = clock.instant();

        // 앞으로 가는 만큼은 실제로 흐른 시간 안에서만 받는다. 뒤로 가는 것은 언제든 받는다.
        // 이게 없으면 영상을 열지 않고 위치만 끝으로 보내 수료할 수 있다.
        int previous = enrolled.progress.getPosition() == null ? 0 : enrolled.progress.getPosition();
        long elapsed = classroomRepository.findPositionAt(lid, currentUser.getUserId())
                .map(at -> Math.max(0, Duration.between(at.toInstant(), now).getSeconds()))
                .orElse(0L);
        long allowed = previous + (long) Math.ceil(elapsed * MAX_PLAYBACK_SPEED) + POSITION_SLACK_SEC;
        if (position > allowed) {
            position = (int) Math.min(allowed, Integer.MAX_VALUE);
        }

        Integer duration = enrolled.lecture.getDuration();
        if (duration != null && position > duration) {
            position = duration;
        }

        classroomRepository.updatePosition(lid, currentUser.getUserId(), position, Timestamp.from(now));
    }

    /** 플레이어가 허용하는 가장 빠른 재생 속도. 이보다 빨리 위치가 앞으로 가면 건너뛴 것이다. */
    static final double MAX_PLAYBACK_SPEED = 2.0;

    /**
     * 저장 간격 · 네트워크 지연 · 짧은 건너뛰기를 받아 주는 여유(초).
     * 처음 저장할 때(이전 저장 시각이 없을 때)는 이 값까지만 앞으로 갈 수 있다.
     */
    static final int POSITION_SLACK_SEC = 30;

    /** 테스트에서 시각을 고정할 때만 쓴다. */
    void setClock(Clock clock) {
        this.clock = clock;
    }

    /**
     * POST /lectures/{Lid}/complete — 수료 처리.
     *
     * 프론트가 "다 봤다"고 알려 주는 것을 그대로 믿지 않는다. 저장된 position 을
     * 기준으로 서버가 판단한다. 믿어 버리면 영상을 켜지 않고 이 API 만 불러
     * 수료증을 받을 수 있다.
     *
     * 이미 수료한 건은 409 다. 다시 처리하면 수료 시각이 덮인다.
     */
    public CompleteResponse complete(int lid, CurrentUser currentUser) {
        Enrolled enrolled = requireEnrolled(lid, currentUser);
        Integer duration = enrolled.lecture.getDuration();
        Integer position = enrolled.progress.getPosition();

        if (enrolled.progress.isDone()) {
            throw new ApiException(HttpStatus.CONFLICT, "ALREADY_COMPLETED", "이미 수료한 강의입니다.");
        }

        // 길이가 없으면 비율을 계산할 수 없다. 이때 조건 없이 수료시키면 이 API 하나만
        // 불러 수료증을 받을 수 있으므로 거절한다. 강사가 길이를 등록하면 풀린다.
        if (duration == null || duration <= 0) {
            throw new ApiException(HttpStatus.CONFLICT, "DURATION_NOT_SET",
                    "영상 길이가 등록되지 않아 수료를 처리할 수 없습니다.");
        }
        int required = (int) Math.ceil(duration * COMPLETE_RATIO);

        int watched = position == null ? 0 : position;
        if (watched < required) {
            throw new ApiException(HttpStatus.CONFLICT, "NOT_WATCHED_ENOUGH",
                    "영상을 끝까지 보셔야 수료로 처리됩니다.");
        }

        int changed = classroomRepository.markDone(lid, currentUser.getUserId());
        if (changed == 0) {
            // 위에서 done 을 확인한 뒤 다른 요청이 먼저 수료 처리한 경우다.
            // 조건절의 done = 0 이 막아 준 것이므로 같은 응답으로 돌려준다.
            throw new ApiException(HttpStatus.CONFLICT, "ALREADY_COMPLETED", "이미 수료한 강의입니다.");
        }

        return new CompleteResponse(lid, true, position, duration, required);
    }

    /**
     * GET /lectures/{Lid}/learn/video — 영상 파일 자체를 내려보낸다.
     *
     * learn 응답이 가리키는 주소가 바로 여기다. 영상 경로를 응답에 담지 않고
     * 이 엔드포인트를 두는 이유는, 경로를 내보내면 그 주소를 아는 사람이
     * 수강을 끊은 뒤에도 계속 받아 갈 수 있기 때문이다. 여기로 받게 하면
     * 매 요청마다 수강 여부를 다시 확인한다.
     */
    public StoredFile videoFile(int lid, CurrentUser currentUser) {
        ClassroomRow lecture = requireEnrolled(lid, currentUser).lecture;

        Path path = storage.resolve(lecture.getVideo(),
                "VIDEO_NOT_FOUND", "등록된 강의 영상이 없습니다.");

        // 보여 줄 이름이 없으면 강의명으로 대신한다. material 과 같은 규칙이다.
        String name = lecture.getVideoFilename() == null
                ? lecture.getName()
                : lecture.getVideoFilename();
        return new StoredFile(path, name);
    }

    /**
     * GET /lectures/{Lid}/material/download — 강의 자료 파일 자체를 내려보낸다.
     *
     * material 응답의 downloadUrl 이 가리키는 주소다. 영상과 같은 이유로
     * 경로가 아니라 엔드포인트를 준다.
     */
    public StoredFile materialFile(int lid, CurrentUser currentUser) {
        ClassroomRow lecture = requireEnrolled(lid, currentUser).lecture;

        Path path = storage.resolve(lecture.getMaterial(),
                "MATERIAL_NOT_FOUND", "등록된 강의 자료가 없습니다.");

        String name = lecture.getMaterialFilename() == null
                ? lecture.getName()
                : lecture.getMaterialFilename();
        return new StoredFile(path, name);
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
                .map(row -> toSubmissionResponse(lid, row))
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

        return toSubmissionResponse(lid, row);
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

        // 조회와 수정 사이에 강사가 채점했으면 수정 쿼리의 score IS NULL 조건에 걸려 0행이다.
        if (classroomRepository.updateSubmission(lid, currentUser.getUserId(), aid,
                request.getWork(), request.getWorkFilename(), request.getNote()) == 0) {
            throw scoredOrGone(lid, currentUser.getUserId(), aid);
        }

        return toSubmissionResponse(lid, classroomRepository
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
            throw scoredOrGone(lid, currentUser.getUserId(), aid);
        }
    }

    /** 수정·삭제가 0행일 때 이유를 가린다. 그 사이 채점됐으면 409, 사라졌으면 404 다. */
    private ApiException scoredOrGone(int lid, int userId, int aid) {
        return classroomRepository.findMySubmissionById(lid, userId, aid)
                .filter(row -> row.getScore() != null)
                .map(row -> new ApiException(HttpStatus.CONFLICT, "ALREADY_SCORED",
                        "채점이 끝난 제출물은 바꿀 수 없습니다."))
                .orElseGet(() -> new ApiException(HttpStatus.NOT_FOUND, "SUBMISSION_NOT_FOUND",
                        "제출물을 찾을 수 없습니다."));
    }

    /**
     * GET /lectures/{Lid}/assignments/{aid}/file — 내가 낸 과제 파일을 내려받는다.
     *
     * 본인 것만 받는다. 남의 제출 번호를 넣으면 403 이 아니라 404 다 — 다른 제출 API 와 같은 규칙이다.
     * 파일 없이 내용만 낸 제출물이면 SUBMISSION_FILE_NOT_FOUND 로 답한다.
     */
    public StoredFile submissionFile(int lid, int aid, CurrentUser currentUser) {
        requireEnrolled(lid, currentUser);
        if (aid < 1) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "제출 번호가 올바르지 않습니다.");
        }
        String[] file = classroomRepository.findMyWorkFile(lid, currentUser.getUserId(), aid)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND, "SUBMISSION_NOT_FOUND", "제출물을 찾을 수 없습니다."));

        Path path = storage.resolve(file[0], "SUBMISSION_FILE_NOT_FOUND", "제출한 파일이 없습니다.");
        return new StoredFile(path, file[1] == null ? "submission" : file[1]);
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
        // 파일과 내용 중 하나만 있으면 된다. 화면이 "파일 없이 내용만 제출"을 허용한다.
        if (request == null || (isBlank(request.getWork()) && isBlank(request.getNote()))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "파일을 첨부하거나 내용을 입력해 주세요.");
        }
        // 파일 없이 낸 경우 원본 파일명만 남지 않게 함께 비운다. 남으면 화면에 있지도 않은
        // 첨부파일 이름이 나오고 다운로드 주소까지 생긴다.
        if (isBlank(request.getWork())) {
            request.setWork(null);
            request.setWorkFilename(null);
        }
        if (length(request.getWork()) > 500 || length(request.getWorkFilename()) > 255) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "파일 정보가 너무 깁니다.");
        }
        // Wnote 는 CLOB 이라 DB 한도는 없다. 화면이 받는 3000자로 맞춘다.
        if (length(request.getNote()) > 3000) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "내용은 3000자까지 입력할 수 있습니다.");
        }
    }

    private AssignmentResponse.SubmissionResponse toSubmissionResponse(int lid, SubmissionRow row) {
        // 원본 파일명이 있으면 파일을 낸 것이다. validate 가 파일 없이 낸 제출의 파일명을 비운다.
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
