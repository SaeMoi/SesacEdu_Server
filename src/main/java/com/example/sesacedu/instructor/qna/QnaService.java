package com.example.sesacedu.instructor.qna;

import com.example.sesacedu.instructor.qna.dto.QnaDetail;
import com.example.sesacedu.instructor.qna.dto.QnaListItem;
import com.example.sesacedu.instructor.qna.dto.QnaPageResponse;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 강사 Q&A 로직.
 *   - 목록(페이지네이션 + status 필터) / 상세 조회
 *   - 답변 저장(작성·수정 공통) / 삭제
 * 권한: 해당 질문이 그 강사의 담당 강의에 속해야 한다.
 */
@Service
public class QnaService {

    private static final int PAGE_SIZE = 5;   // 페이지당 고정

    private final QnaRepository qnaRepository;

    public QnaService(QnaRepository qnaRepository) {
        this.qnaRepository = qnaRepository;
    }

    /** 목록 (페이지네이션 + status 필터) */
    public QnaPageResponse getQnaList(long instructorUserId, String status, int page) {
        int safePage = Math.max(page, 1);
        int offset = (safePage - 1) * PAGE_SIZE;

        List<QnaListItem> items = qnaRepository.findQna(instructorUserId, status, offset, PAGE_SIZE);
        int total = qnaRepository.countQna(instructorUserId, status);
        int totalPages = total == 0 ? 0 : (int) Math.ceil((double) total / PAGE_SIZE);

        return new QnaPageResponse(safePage, PAGE_SIZE, total, totalPages, items);
    }

    /** 상세. 없거나 권한 없으면 예외. */
    public QnaDetail getDetail(int qid, long instructorUserId) {
        QnaDetail detail = qnaRepository.findDetail(qid, instructorUserId);
        if (detail == null) {
            throw new NotFoundOrForbidden();
        }
        return detail;
    }

    /** 답변 저장 (작성·수정 공통) */
    public void saveAnswer(int qid, long instructorUserId, String answer) {
        if (!qnaRepository.isOwnedByInstructor(qid, instructorUserId)) {
            throw new NotFoundOrForbidden();
        }
        qnaRepository.saveAnswer(qid, instructorUserId, answer);
    }

    /** 답변 삭제 (answer 등을 NULL 로) */
    public void deleteAnswer(int qid, long instructorUserId) {
        if (!qnaRepository.isOwnedByInstructor(qid, instructorUserId)) {
            throw new NotFoundOrForbidden();
        }
        qnaRepository.clearAnswer(qid);
    }

    /** 질문이 없거나(=404) 담당 강사가 아님(=권한 없음). 컨트롤러에서 404 로 변환. */
    public static class NotFoundOrForbidden extends RuntimeException {
    }
}