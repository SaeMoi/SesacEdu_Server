package com.example.sesacedu.instructor.feedback;

import com.example.sesacedu.global.storage.FileStorage;
import com.example.sesacedu.instructor.feedback.dto.AssignmentDetail;
import com.example.sesacedu.instructor.feedback.dto.AssignmentDownload;
import com.example.sesacedu.instructor.feedback.dto.AssignmentListItem;
import com.example.sesacedu.instructor.feedback.dto.AssignmentPageResponse;
import com.example.sesacedu.student.lecture.LectureFileStorage;
import com.example.sesacedu.student.lecture.StoredFile;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 과제 피드백 로직.
 *   - 목록(페이지네이션 + status 필터) / 상세 조회
 *   - 과제 파일 다운로드 (URL 발급)
 *   - 피드백 저장(작성·수정 공통) / 삭제
 * 권한: 해당 과제가 그 강사의 담당 강의에 속해야 한다.
 */
@Service
public class FeedbackService {

    private static final int PAGE_SIZE = 5;   // 페이지당 고정

    private final FeedbackRepository feedbackRepository;
    private final FileStorage fileStorage;
    private final LectureFileStorage lectureFileStorage;

    public FeedbackService(FeedbackRepository feedbackRepository, FileStorage fileStorage,
                           LectureFileStorage lectureFileStorage) {
        this.feedbackRepository = feedbackRepository;
        this.fileStorage = fileStorage;
        this.lectureFileStorage = lectureFileStorage;
    }

    /** 목록 (페이지네이션 + status 필터) */
    public AssignmentPageResponse getAssignments(long instructorUserId, String status, int page) {
        int safePage = Math.max(page, 1);
        int offset = (safePage - 1) * PAGE_SIZE;

        List<AssignmentListItem> items =
                feedbackRepository.findAssignments(instructorUserId, status, offset, PAGE_SIZE);
        int total = feedbackRepository.countAssignments(instructorUserId, status);
        int totalPages = total == 0 ? 0 : (int) Math.ceil((double) total / PAGE_SIZE);

        return new AssignmentPageResponse(safePage, PAGE_SIZE, total, totalPages, items);
    }

    /** 상세. 없거나 권한 없으면 예외. */
    public AssignmentDetail getDetail(int aid, long instructorUserId) {
        AssignmentDetail detail = feedbackRepository.findDetail(aid, instructorUserId);
        if (detail == null) {
            throw new NotFoundOrForbidden();
        }
        return detail;
    }

    /** 과제 제출 파일 다운로드 정보(원본명 + URL). */
    public AssignmentDownload getDownload(int aid, long instructorUserId) {
        FeedbackRepository.FileInfo info = feedbackRepository.findFileInfo(aid, instructorUserId);
        if (info == null) {
            throw new NotFoundOrForbidden();
        }
        if (info.workPath == null || info.workPath.isBlank()) {
            throw new FileNotFound();
        }
        // 실제 파일은 GET /instructor/assignments/{aid}/file 이 보낸다 (Authorization 필요)
        return new AssignmentDownload(info.work, "/instructor/assignments/" + aid + "/file");
    }

    /** 과제 제출 파일 자체. 저장소에 파일이 없으면 FileNotFound. */
    public StoredFile getFile(int aid, long instructorUserId) {
        FeedbackRepository.FileInfo info = feedbackRepository.findFileInfo(aid, instructorUserId);
        if (info == null) {
            throw new NotFoundOrForbidden();
        }
        if (info.workPath == null || info.workPath.isBlank()) {
            throw new FileNotFound();
        }
        try {
            return new StoredFile(
                    lectureFileStorage.resolve(info.workPath, "SUBMISSION_FILE_NOT_FOUND", "제출 파일이 없습니다."),
                    info.work == null ? "assignment" : info.work);
        } catch (com.example.sesacedu.global.exception.ApiException e) {
            throw new FileNotFound();
        }
    }

    /** 피드백 저장 (작성·수정 공통) */
    public void saveFeedback(int aid, long instructorUserId, Integer score, String feedback) {
        if (!feedbackRepository.isOwnedByInstructor(aid, instructorUserId)) {
            throw new NotFoundOrForbidden();
        }
        feedbackRepository.saveFeedback(aid, score, feedback);
    }

    /** 피드백 삭제 (score/feedback 를 NULL 로) */
    public void deleteFeedback(int aid, long instructorUserId) {
        if (!feedbackRepository.isOwnedByInstructor(aid, instructorUserId)) {
            throw new NotFoundOrForbidden();
        }
        feedbackRepository.clearFeedback(aid);
    }

    /** 과제가 없거나(=404) 담당 강사가 아님(=권한 없음). 컨트롤러에서 404 로 변환. */
    public static class NotFoundOrForbidden extends RuntimeException {
    }

    /** 과제는 있으나 제출 파일이 없어 다운로드 불가 (404). */
    public static class FileNotFound extends RuntimeException {
    }
}