package com.example.sesacedu.instructor.feedback;

import com.example.sesacedu.global.storage.FileStorage;
import com.example.sesacedu.instructor.feedback.dto.AssignmentDetail;
import com.example.sesacedu.instructor.feedback.dto.AssignmentDownload;
import com.example.sesacedu.instructor.feedback.dto.AssignmentListItem;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 과제 피드백 로직.
 *   - 목록 / 상세 조회
 *   - 과제 파일 다운로드 (URL 발급)
 *   - 피드백 저장(작성·수정 공통) / 삭제
 * 권한: 해당 과제가 그 강사의 담당 강의에 속해야 한다.
 */
@Service
public class FeedbackService {

    private final FeedbackRepository feedbackRepository;
    private final FileStorage fileStorage;

    public FeedbackService(FeedbackRepository feedbackRepository, FileStorage fileStorage) {
        this.feedbackRepository = feedbackRepository;
        this.fileStorage = fileStorage;
    }

    /** 목록 */
    public List<AssignmentListItem> getAssignments(int instructorUserId, Integer lectureId) {
        return feedbackRepository.findAssignments(instructorUserId, lectureId);
    }

    /** 상세. 없거나 권한 없으면 예외. */
    public AssignmentDetail getDetail(int aid, int instructorUserId) {
        AssignmentDetail detail = feedbackRepository.findDetail(aid, instructorUserId);
        if (detail == null) {
            // 과제가 아예 없는지, 남의 강의라 권한이 없는지 구분해서 던진다.
            throw new NotFoundOrForbidden();
        }
        return detail;
    }

    /**
     * 과제 제출 파일 다운로드 정보(원본명 + URL)를 돌려준다.
     * 경로 → 다운로드 URL 변환은 FileStorage 가 담당한다(S3 붙으면 그 구현만 교체).
     */
    public AssignmentDownload getDownload(int aid, int instructorUserId) {
        FeedbackRepository.FileInfo info = feedbackRepository.findFileInfo(aid, instructorUserId);
        if (info == null) {
            throw new NotFoundOrForbidden();
        }
        if (info.workPath == null || info.workPath.isBlank()) {
            // 제출 파일 경로가 없으면 받을 수 없다.
            throw new FileNotFound();
        }
        String url = fileStorage.getDownloadUrl(info.workPath);
        return new AssignmentDownload(info.work, url);
    }

    /** 피드백 저장 (작성·수정 공통) */
    public void saveFeedback(int aid, int instructorUserId, Integer score, String feedback) {
        if (!feedbackRepository.isOwnedByInstructor(aid, instructorUserId)) {
            throw new NotFoundOrForbidden();
        }
        feedbackRepository.saveFeedback(aid, score, feedback);
    }

    /** 피드백 삭제 (feedback/score 를 NULL 로) */
    public void deleteFeedback(int aid, int instructorUserId) {
        if (!feedbackRepository.isOwnedByInstructor(aid, instructorUserId)) {
            throw new NotFoundOrForbidden();
        }
        feedbackRepository.clearFeedback(aid);
    }

    /**
     * 과제가 없거나(=404) 담당 강사가 아님(=권한 없음).
     * 컨트롤러에서 404 로 변환한다.
     * (존재 여부를 노출하지 않기 위해 둘을 404 하나로 합쳐서 다룬다.)
     */
    public static class NotFoundOrForbidden extends RuntimeException {
    }

    /** 과제는 있으나 제출 파일이 없어 다운로드 불가 (404). */
    public static class FileNotFound extends RuntimeException {
    }
}