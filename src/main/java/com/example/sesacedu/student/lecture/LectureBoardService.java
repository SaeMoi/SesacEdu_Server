package com.example.sesacedu.student.lecture;

import com.example.sesacedu.global.auth.CurrentUser;
import com.example.sesacedu.global.exception.ApiException;
import com.example.sesacedu.student.lecture.dto.PageResponse;
import com.example.sesacedu.student.lecture.dto.QnaDetailResponse;
import com.example.sesacedu.student.lecture.dto.QnaListResponse;
import com.example.sesacedu.student.lecture.dto.ReviewListResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 강의에 딸린 글(수강평 · Q&A)의 판단을 담당한다.
 *
 * 셋 다 공개 API 다. 로그인하지 않아도 볼 수 있다. 다만 비밀글은 가린다.
 * 가리는 기준이 목록과 상세 두 곳에 있으므로 canRead 한 곳에 모아 뒀다.
 */
@Service
public class LectureBoardService {

    static final int DEFAULT_PAGE = 1;
    /** 글 목록은 카드 목록보다 짧게 끊는다. 화면에서 한 번에 10개씩 보여 준다. */
    static final int DEFAULT_SIZE = 10;
    static final int MAX_SIZE = 50;

    /** 볼 수 없는 비밀글의 제목 자리에 넣는 고정 문구. 실제 제목은 담지 않는다. */
    static final String SECRET_TITLE = "비밀글입니다.";

    private final LectureRepository lectureRepository;
    private final LectureBoardRepository boardRepository;

    public LectureBoardService(LectureRepository lectureRepository,
                               LectureBoardRepository boardRepository) {
        this.lectureRepository = lectureRepository;
        this.boardRepository = boardRepository;
    }

    /** GET /lectures/{Lid}/reviews — 수강평 목록. */
    public ReviewListResponse reviews(int lid, Integer page, Integer size) {
        Paging paging = resolvePaging(lid, page, size);

        // 평균 평점은 전체 기준이라 현재 페이지와 무관하게 따로 구한다.
        ReviewSummary summary = lectureRepository.findReviewSummary(lid);
        int total = boardRepository.countReviews(lid);

        List<ReviewListResponse.ReviewItem> items = total == 0
                ? Collections.emptyList()
                : boardRepository.findReviews(lid, paging.offset, paging.size).stream()
                .map(row -> new ReviewListResponse.ReviewItem(
                        row.getUserId(), row.getNickname(), row.getStar(),
                        row.getReview(), row.getCreated()))
                .collect(Collectors.toList());

        return new ReviewListResponse(items, paging.toPageResponse(total),
                summary.getRating(), summary.getReviewCount());
    }

    /** GET /lectures/{Lid}/qna — Q&A 목록. 비밀글은 제목을 가린다. */
    public QnaListResponse qna(int lid, Integer page, Integer size, CurrentUser currentUser) {
        Paging paging = resolvePaging(lid, page, size);

        int total = boardRepository.countQna(lid);
        if (total == 0) {
            return new QnaListResponse(Collections.emptyList(), paging.toPageResponse(0));
        }

        int instructorUserId = instructorUserId(lid);

        List<QnaListResponse.QnaItem> items = boardRepository.findQna(lid, paging.offset, paging.size)
                .stream()
                .map(row -> {
                    boolean accessible = canRead(row, currentUser, instructorUserId);
                    return new QnaListResponse.QnaItem(
                            row.getQid(),
                            // 볼 수 없는 비밀글은 제목 자리에 고정 문구를 넣는다.
                            accessible ? row.getTitle() : SECRET_TITLE,
                            row.getNickname(),
                            row.isSecret(),
                            accessible,
                            // 답변 본문이 아니라 "달렸는지" 만 내보낸다.
                            row.getAnswer() != null || row.getAnsweredBy() != null,
                            row.getCreated());
                })
                .collect(Collectors.toList());

        return new QnaListResponse(items, paging.toPageResponse(total));
    }

    /** GET /lectures/{Lid}/qna/{qnaid} — Q&A 상세. 비밀글은 본인과 담당 강사만 볼 수 있다. */
    public QnaDetailResponse qnaDetail(int lid, int qid, CurrentUser currentUser) {
        requireOpenLecture(lid);
        if (qid < 1) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "질문 번호가 올바르지 않습니다.");
        }

        QnaRow row = boardRepository.findQnaDetail(lid, qid)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND, "QNA_NOT_FOUND", "질문을 찾을 수 없습니다."));

        // 목록에서 accessible=false 로 내려보냈더라도 여기서 다시 막는다.
        // 프론트가 링크를 숨기는 것은 표시일 뿐이고, 주소를 직접 치면 그대로 들어온다.
        if (!canRead(row, currentUser, instructorUserId(lid))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "QNA_SECRET", "비밀글은 작성자와 담당 강사만 볼 수 있습니다.");
        }

        return new QnaDetailResponse(
                row.getQid(), lid, row.getUserId(), row.getNickname(),
                row.getTitle(), row.getQuestion(), row.getAnswer(),
                row.getAnsweredBy(), row.getAnsweredAt(), row.isSecret(), row.getCreated());
    }

    /**
     * 비밀글을 볼 수 있는가.
     *
     * 비밀글이 아니면 누구나 본다. 비밀글이면 질문자 본인이거나 그 강의의 담당 강사여야 한다.
     * 로그인하지 않았으면 볼 수 없다.
     *
     * users.auth 로 "강사면 통과" 시키지 않는 것이 중요하다. auth=2 는 "강사다"라는
     * 뜻일 뿐이고 "이 강의의 강사다"가 아니다. 다른 강사가 남의 강의 비밀글을 읽게 된다.
     */
    private boolean canRead(QnaRow row, CurrentUser currentUser, int instructorUserId) {
        if (!row.isSecret()) {
            return true;
        }
        if (currentUser == null) {
            return false;
        }
        return currentUser.getUserId() == row.getUserId()
                || currentUser.getUserId() == instructorUserId;
    }

    private int instructorUserId(int lid) {
        return boardRepository.findInstructorUserId(lid).orElse(-1);
    }

    /**
     * 강의가 공개 상태인지 확인한다.
     *
     * 강의 상세와 같은 기준을 쓰려고 LectureRepository 를 그대로 부른다. 공개 기준이
     * 두 군데로 갈라지면 한쪽만 고쳤을 때 비공개 강의의 글이 새어 나간다.
     */
    private void requireOpenLecture(int lid) {
        if (lid < 1) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "강의 번호가 올바르지 않습니다.");
        }
        if (!lectureRepository.findOpenById(lid).isPresent()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "LECTURE_NOT_FOUND", "강의를 찾을 수 없습니다.");
        }
    }

    /** 페이지 값 검사와 offset 계산. 강의 목록과 같은 방식이다. */
    private Paging resolvePaging(int lid, Integer page, Integer size) {
        requireOpenLecture(lid);

        int resolvedPage = page == null ? DEFAULT_PAGE : page;
        int resolvedSize = size == null ? DEFAULT_SIZE : size;
        if (resolvedPage < 1 || resolvedSize < 1 || resolvedSize > MAX_SIZE) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PAGE", "페이지 조건이 올바르지 않습니다.");
        }

        // int 로 곱하면 큰 page 에서 넘쳐 음수가 되고 위 검사를 통과해 버린다.
        long offset = (long) (resolvedPage - 1) * resolvedSize;
        if (offset > Integer.MAX_VALUE) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PAGE", "페이지 조건이 올바르지 않습니다.");
        }
        return new Paging(resolvedPage, resolvedSize, (int) offset);
    }

    private static final class Paging {
        private final int page;
        private final int size;
        private final int offset;

        private Paging(int page, int size, int offset) {
            this.page = page;
            this.size = size;
            this.offset = offset;
        }

        private PageResponse toPageResponse(int total) {
            int totalPages = total == 0 ? 0 : (int) Math.ceil(total / (double) size);
            return new PageResponse(page, size, total, totalPages);
        }
    }
}
