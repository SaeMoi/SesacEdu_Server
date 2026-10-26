package com.example.sesacedu.instructor.qna;

import com.example.sesacedu.instructor.qna.dto.QnaDetail;
import com.example.sesacedu.instructor.qna.dto.QnaListItem;
import com.example.sesacedu.instructor.qna.dto.QnaPageResponse;
import org.springframework.stereotype.Service;

import java.util.List;

/*
 * ===================== [취약 진단용 - qna] =====================
 *  (V-AUTHZ/A01) 소유권 검사 제거 : saveAnswer/deleteAnswer 의 isOwnedByInstructor 삭제,
 *     상세도 리포지토리에서 담당강의 조건 제거 → 비밀글 포함 타 강사 질문 열람·답변·삭제(IDOR).
 * ================================================================
 */
@Service
public class QnaService {

    private static final int PAGE_SIZE = 5;   // 페이지당 고정

    private final QnaRepository qnaRepository;

    public QnaService(QnaRepository qnaRepository) {
        this.qnaRepository = qnaRepository;
    }

    public QnaPageResponse getQnaList(long instructorUserId, String status, int page) {
        int safePage = Math.max(page, 1);
        int offset = (safePage - 1) * PAGE_SIZE;

        List<QnaListItem> items = qnaRepository.findQna(instructorUserId, status, offset, PAGE_SIZE);
        int total = qnaRepository.countQna(instructorUserId, status);
        int totalPages = total == 0 ? 0 : (int) Math.ceil((double) total / PAGE_SIZE);

        return new QnaPageResponse(safePage, PAGE_SIZE, total, totalPages, items);
    }

    /** 상세. [V-AUTHZ] 담당강의 검증 없음(IDOR). */
    public QnaDetail getDetail(int qid, long instructorUserId) {
        QnaDetail detail = qnaRepository.findDetail(qid, instructorUserId);
        if (detail == null) {
            throw new NotFoundOrForbidden();
        }
        return detail;
    }

    /** 답변 저장. [V-AUTHZ] 소유권 검사 제거. */
    public void saveAnswer(int qid, long instructorUserId, String answer) {
        qnaRepository.saveAnswer(qid, instructorUserId, answer);
    }

    /** 답변 삭제. [V-AUTHZ] 소유권 검사 제거. */
    public void deleteAnswer(int qid, long instructorUserId) {
        qnaRepository.clearAnswer(qid);
    }

    /** 질문이 없음(=404). */
    public static class NotFoundOrForbidden extends RuntimeException {
    }
}