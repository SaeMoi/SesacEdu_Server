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

/*
 * ===================== [취약 진단용 - feedback] =====================
 *  (V-AUTHZ/A01) 소유권 검사 제거 : saveFeedback/deleteFeedback 의 isOwnedByInstructor 삭제.
 *     조회/다운로드도 리포지토리에서 담당강의 조건 제거 → 다른 강사 학생의 제출물 열람·다운로드·채점·삭제(IDOR).
 * ================================================================
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

    /** 상세. [V-AUTHZ] 담당강의 검증 없음(IDOR). */
    public AssignmentDetail getDetail(int aid, long instructorUserId) {
        AssignmentDetail detail = feedbackRepository.findDetail(aid, instructorUserId);
        if (detail == null) {
            throw new NotFoundOrForbidden();
        }
        return detail;
    }

    /** 과제 제출 파일 다운로드 정보(원본명 + URL). [V-AUTHZ] 담당강의 검증 없음. */
    public AssignmentDownload getDownload(int aid, long instructorUserId) {
        FeedbackRepository.FileInfo info = feedbackRepository.findFileInfo(aid, instructorUserId);
        if (info == null) {
            throw new NotFoundOrForbidden();
        }
        if (info.workPath == null || info.workPath.isBlank()) {
            throw new FileNotFound();
        }
        return new AssignmentDownload(info.work, "/instructor/assignments/" + aid + "/file");
    }

    /** 과제 제출 파일 자체. [V-AUTHZ] 담당강의 검증 없음. */
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

    /** 피드백 저장. [V-AUTHZ] 소유권 검사 제거 → 아무 과제나 채점 가능. */
    public void saveFeedback(int aid, long instructorUserId, Integer score, String feedback) {
        feedbackRepository.saveFeedback(aid, score, feedback);
    }

    /** 피드백 삭제. [V-AUTHZ] 소유권 검사 제거. */
    public void deleteFeedback(int aid, long instructorUserId) {
        feedbackRepository.clearFeedback(aid);
    }

    /** 과제가 없음(=404). */
    public static class NotFoundOrForbidden extends RuntimeException {
    }

    /** 과제는 있으나 제출 파일이 없어 다운로드 불가 (404). */
    public static class FileNotFound extends RuntimeException {
    }
}