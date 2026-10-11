package com.example.sesacedu.instructor.qna;

import com.example.sesacedu.instructor.qna.dto.QnaDetail;
import com.example.sesacedu.instructor.qna.dto.QnaListItem;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 강사 Q&A 로직.
 *   - 목록 / 상세 조회
 *   - 답변 저장(작성·수정 공통) / 삭제
 * 권한: 해당 질문이 그 강사의 담당 강의에 속해야 한다.
 */
@Service
public class QnaService {

    private final QnaRepository qnaRepository;

    public QnaService(QnaRepository qnaRepository) {
        this.qnaRepository = qnaRepository;
    }

    /** 목록 (status: none/done/null) */
    public List<QnaListItem> getList(int instructorUserId, String status) {
        return qnaRepository.findList(instructorUserId, status);
    }

    /** 상세. 없거나 권한 없으면 예외. */
    public QnaDetail getDetail(int qid, int instructorUserId) {
        QnaDetail detail = qnaRepository.findDetail(qid, instructorUserId);
        if (detail == null) {
            throw new NotFoundOrForbidden();
        }
        return detail;
    }

    /** 답변 저장 (작성·수정 공통). answered_by 에 강사 user_id 기록. */
    public void saveAnswer(int qid, int instructorUserId, String answer) {
        if (!qnaRepository.isOwnedByInstructor(qid, instructorUserId)) {
            throw new NotFoundOrForbidden();
        }
        qnaRepository.saveAnswer(qid, answer, instructorUserId);
    }

    /** 답변 삭제 (answer/answered_by 를 NULL 로). */
    public void deleteAnswer(int qid, int instructorUserId) {
        if (!qnaRepository.isOwnedByInstructor(qid, instructorUserId)) {
            throw new NotFoundOrForbidden();
        }
        qnaRepository.clearAnswer(qid);
    }

    /** 질문이 없거나(=404) 담당 강사가 아님. 컨트롤러에서 404 로 변환. */
    public static class NotFoundOrForbidden extends RuntimeException {
    }
}