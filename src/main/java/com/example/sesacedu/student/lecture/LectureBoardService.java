package com.example.sesacedu.student.lecture;

import com.example.sesacedu.global.auth.CurrentUser;
import com.example.sesacedu.global.exception.ApiException;
import com.example.sesacedu.student.lecture.dto.CreatedResponse;
import com.example.sesacedu.student.lecture.dto.PageResponse;
import com.example.sesacedu.student.lecture.dto.QnaDetailResponse;
import com.example.sesacedu.student.lecture.dto.QnaListResponse;
import com.example.sesacedu.student.lecture.dto.QnaWriteRequest;
import com.example.sesacedu.student.lecture.dto.ReviewListResponse;
import com.example.sesacedu.student.lecture.dto.ReviewWriteRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 강의에 딸린 글(수강평 · Q&A) 처리. 목록과 상세는 로그인하지 않아도 본다.
 */
@Service
public class LectureBoardService {

    static final int DEFAULT_PAGE = 1;
    static final int DEFAULT_SIZE = 10;
    static final int MAX_SIZE = 50;

    /** 목록에서 비밀글 제목 대신 보여 주는 문구. */
    static final String SECRET_TITLE = "비밀글입니다.";

    private final LectureRepository lectureRepository;
    private final LectureBoardRepository boardRepository;

    public LectureBoardService(LectureRepository lectureRepository,
                               LectureBoardRepository boardRepository) {
        this.lectureRepository = lectureRepository;
        this.boardRepository = boardRepository;
    }

    /** GET /lectures/{Lid}/reviews — 수강평 목록과 평균 평점. */
    public ReviewListResponse reviews(int lid, Integer page, Integer size, CurrentUser currentUser) {
        Paging paging = resolvePaging(lid, page, size);

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

    /** GET /lectures/{Lid}/qna — Q&A 목록. 비밀글은 작성자와 담당 강사가 아니면 제목을 가린다. */
    public QnaListResponse qna(int lid, Integer page, Integer size, String status,
                               CurrentUser currentUser) {
        Paging paging = resolvePaging(lid, page, size);
        String filter = resolveQnaStatus(status);

        int total = boardRepository.countQna(lid, filter);
        if (total == 0) {
            return new QnaListResponse(Collections.emptyList(), paging.toPageResponse(0));
        }

        int instructorUserId = boardRepository.findInstructorUserId(lid).orElse(-1);

        List<QnaListResponse.QnaItem> items = boardRepository.findQna(lid, paging.offset, paging.size, filter)
                .stream()
                .map(row -> {
                    boolean accessible = !row.isSecret()
                            || (currentUser != null && (currentUser.getUserId() == row.getUserId()
                            || currentUser.getUserId() == instructorUserId));
                    return new QnaListResponse.QnaItem(
                            row.getQid(),
                            accessible ? row.getTitle() : SECRET_TITLE,
                            row.getNickname(),
                            row.isSecret(),
                            accessible,
                            row.getAnswer() != null || row.getAnsweredBy() != null,
                            row.getCreated());
                })
                .collect(Collectors.toList());

        return new QnaListResponse(items, paging.toPageResponse(total));
    }

    /** GET /lectures/{Lid}/qna/{qnaid} — Q&A 상세. */
    public QnaDetailResponse qnaDetail(int lid, int qid, CurrentUser currentUser) {
        requireOpenLecture(lid);

        QnaRow row = boardRepository.findQnaDetail(lid, qid)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND, "QNA_NOT_FOUND", "질문을 찾을 수 없습니다."));

        // 비밀글은 작성자와 담당 강사만 본다.
        if (row.isSecret()) {
            if (currentUser == null) {
                throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "로그인이 필요합니다.");
            }
            int instructorUserId = boardRepository.findInstructorUserId(lid).orElse(-1);
            if (currentUser.getUserId() != row.getUserId() && currentUser.getUserId() != instructorUserId) {
                throw new ApiException(HttpStatus.FORBIDDEN, "SECRET_QNA", "비밀글은 작성자와 강사만 볼 수 있습니다.");
            }
        }

        return new QnaDetailResponse(
                row.getQid(), lid, row.getUserId(), row.getNickname(),
                row.getTitle(), row.getQuestion(), row.getAnswer(),
                row.getAnsweredBy(), row.getAnsweredAt(), row.isSecret(), row.getCreated());
    }

    /** POST /lectures/{Lid}/reviews — 수강평 작성. 수강자만, 강의당 1인 1건이다. */
    public void writeReview(int lid, CurrentUser currentUser, ReviewWriteRequest request) {
        requireEnrolledWriter(lid, currentUser);
        validateReview(request);

        if (boardRepository.existsReview(lid, currentUser.getUserId())) {
            throw new ApiException(HttpStatus.CONFLICT, "ALREADY_REVIEWED", "이미 수강평을 작성했습니다.");
        }

        boardRepository.insertReview(lid, currentUser.getUserId(), request.getStar(), request.getReview());
    }

    /** PUT /lectures/{Lid}/reviews — 수강평 수정. */
    public void updateReview(int lid, CurrentUser currentUser, ReviewWriteRequest request) {
        requireEnrolledWriter(lid, currentUser);
        validateReview(request);

        if (boardRepository.updateReview(lid, currentUser.getUserId(), request.getStar(), request.getReview()) == 0) {
            throw new ApiException(HttpStatus.NOT_FOUND, "REVIEW_NOT_FOUND", "수정할 수강평이 없습니다.");
        }
    }

    /** DELETE /lectures/{Lid}/reviews — 수강평 삭제. 지운 뒤 다시 쓸 수 있다. */
    public void deleteReview(int lid, CurrentUser currentUser) {
        requireEnrolledWriter(lid, currentUser);

        if (boardRepository.deleteReview(lid, currentUser.getUserId()) == 0) {
            throw new ApiException(HttpStatus.NOT_FOUND, "REVIEW_NOT_FOUND", "삭제할 수강평이 없습니다.");
        }
    }

    /** POST /lectures/{Lid}/qna — 강의 질문 작성. 수강자만 쓴다. */
    public CreatedResponse writeQna(int lid, CurrentUser currentUser, QnaWriteRequest request) {
        requireEnrolledWriter(lid, currentUser);

        if (request == null || request.getTitle() == null || request.getTitle().trim().isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "제목을 입력해 주세요.");
        }
        if (request.getTitle().length() > 200) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "제목은 200자까지 쓸 수 있습니다.");
        }
        if (request.getQuestion() != null && request.getQuestion().length() > 4000) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "본문은 4000자까지 쓸 수 있습니다.");
        }

        // secret 을 안 보내면 공개글이다.
        boolean secret = Boolean.TRUE.equals(request.getSecret());

        int qid = boardRepository.insertQna(lid, currentUser.getUserId(),
                request.getTitle(), request.getQuestion(), secret);
        return new CreatedResponse(qid);
    }

    /** 평점은 1~5, 본문은 2000자까지. */
    private void validateReview(ReviewWriteRequest request) {
        if (request == null || request.getStar() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_STAR", "평점을 입력해 주세요.");
        }
        if (request.getStar() < 1 || request.getStar() > 5) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_STAR", "평점은 1에서 5 사이여야 합니다.");
        }
        if (request.getReview() != null && request.getReview().length() > 2000) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "수강평은 2000자까지 쓸 수 있습니다.");
        }
    }

    /** answered · waiting(unanswered) 만 거른다. 그 밖의 값은 전체다. */
    private String resolveQnaStatus(String status) {
        if (status == null) {
            return null;
        }
        String value = status.trim().toLowerCase();
        if ("answered".equals(value)) {
            return "answered";
        }
        if ("waiting".equals(value) || "unanswered".equals(value)) {
            return "waiting";
        }
        return null;
    }

    /** 글쓰기 공통 확인. 공개 강의 → 로그인 → 수강 여부. */
    private void requireEnrolledWriter(int lid, CurrentUser currentUser) {
        requireOpenLecture(lid);
        if (currentUser == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "로그인이 필요합니다.");
        }
        if (!lectureRepository.findEnrollment(currentUser.getUserId(), lid).isPresent()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "NOT_ENROLLED", "수강 중인 강의가 아닙니다.");
        }
    }

    private void requireOpenLecture(int lid) {
        if (!lectureRepository.findOpenById(lid).isPresent()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "LECTURE_NOT_FOUND", "강의를 찾을 수 없습니다.");
        }
    }

    private Paging resolvePaging(int lid, Integer page, Integer size) {
        requireOpenLecture(lid);

        int resolvedPage = page == null ? DEFAULT_PAGE : page;
        int resolvedSize = size == null ? DEFAULT_SIZE : size;
        if (resolvedPage < 1 || resolvedSize < 1 || resolvedSize > MAX_SIZE) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PAGE", "페이지 조건이 올바르지 않습니다.");
        }
        return new Paging(resolvedPage, resolvedSize, (resolvedPage - 1) * resolvedSize);
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
