package com.example.sesacedu.student.lecture;

import com.example.sesacedu.global.auth.CurrentUser;
import com.example.sesacedu.global.exception.ApiException;
import com.example.sesacedu.student.lecture.dto.CreatedResponse;
import com.example.sesacedu.student.lecture.dto.PageResponse;
import com.example.sesacedu.student.lecture.dto.QnaWriteRequest;
import com.example.sesacedu.student.lecture.dto.ReviewWriteRequest;
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

    /**
     * GET /lectures/{Lid}/reviews — 수강평 목록.
     *
     * 공개 API 라 currentUser 는 null 일 수 있다. 그때 mine 은 전부 false 다.
     */
    public ReviewListResponse reviews(int lid, Integer page, Integer size, CurrentUser currentUser) {
        Paging paging = resolvePaging(lid, page, size);

        // 비로그인은 비교 대상이 없다. 0 은 실제 user_id 로 쓰이지 않는 값이다.
        int viewerId = currentUser == null ? 0 : currentUser.getUserId();

        // 평균 평점은 전체 기준이라 현재 페이지와 무관하게 따로 구한다.
        ReviewSummary summary = lectureRepository.findReviewSummary(lid);
        int total = boardRepository.countReviews(lid);

        List<ReviewListResponse.ReviewItem> items = total == 0
                ? Collections.emptyList()
                : boardRepository.findReviews(lid, paging.offset, paging.size).stream()
                .map(row -> new ReviewListResponse.ReviewItem(
                        row.getUserId() == viewerId, row.getNickname(), row.getStar(),
                        row.getReview(), row.getCreated()))
                .collect(Collectors.toList());

        return new ReviewListResponse(items, paging.toPageResponse(total),
                summary.getRating(), summary.getReviewCount());
    }

    /** GET /lectures/{Lid}/qna — Q&A 목록. 비밀글은 제목을 가린다. */
    public QnaListResponse qna(int lid, Integer page, Integer size, String status,
                               CurrentUser currentUser) {
        Paging paging = resolvePaging(lid, page, size);
        String filter = resolveQnaStatus(status);

        int total = boardRepository.countQna(lid, filter);
        if (total == 0) {
            return new QnaListResponse(Collections.emptyList(), paging.toPageResponse(0));
        }

        int instructorUserId = instructorUserId(lid);

        List<QnaListResponse.QnaItem> items = boardRepository.findQna(lid, paging.offset, paging.size, filter)
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

        boolean mine = currentUser != null && currentUser.getUserId() == row.getUserId();
        return new QnaDetailResponse(
                row.getQid(), lid, mine, row.getNickname(),
                row.getTitle(), row.getQuestion(), row.getAnswer(),
                row.getAnsweredAt(), row.isSecret(), row.getCreated());
    }

    /**
     * POST /lectures/{Lid}/reviews — 수강평 작성.
     *
     * 수강한 사람만 쓸 수 있다. 사지도 않은 강의에 평점을 매기면 평점이
     * 무의미해진다. 수강 여부는 enrollments 로 본다.
     */
    public void writeReview(int lid, CurrentUser currentUser, ReviewWriteRequest request) {
        requireEnrolledWriter(lid, currentUser);

        validateReview(request);

        // reviews 는 (Lid, user_id) 가 PK 라 1인 1건이다. 미리 보지 않으면
        // PK 위반 오류가 그대로 올라온다.
        if (boardRepository.existsReview(lid, currentUser.getUserId())) {
            throw new ApiException(HttpStatus.CONFLICT, "ALREADY_REVIEWED", "이미 수강평을 작성했습니다.");
        }

        boardRepository.insertReview(lid, currentUser.getUserId(),
                request.getStar(), request.getReview());
    }

    /**
     * status 파라미터를 정해진 값으로 좁힌다.
     *
     * 안 보내면 전체다. 모르는 값은 조용히 무시하지 않고 400 으로 돌려준다.
     * 무시하면 "미답변만"을 눌렀는데 전체가 나오고, 사용자는 그걸 필터 결과로 읽는다.
     */
    private String resolveQnaStatus(String status) {
        if (status == null || status.trim().isEmpty()) {
            return null;
        }
        String value = status.trim().toLowerCase();
        // 명세 초안의 all · unanswered 도 받는다. 화면이 어느 쪽으로 짜였든 같은 결과가 나오게 한다.
        if ("all".equals(value)) {
            return null;
        }
        if ("answered".equals(value)) {
            return "answered";
        }
        if ("waiting".equals(value) || "unanswered".equals(value)) {
            return "waiting";
        }
        throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_INPUT",
                "답변 상태는 all · answered · waiting(unanswered) 중 하나입니다.");
    }

    /**
     * PUT /lectures/{Lid}/reviews — 내 수강평 수정.
     *
     * 리뷰 번호를 경로에 두지 않는다. reviews 의 PK 가 (Lid, user_id) 라서
     * "이 강의의 내 리뷰"가 애초에 한 건으로 정해지기 때문이다. 번호를 받으면
     * 남의 번호를 적어 보낼 자리가 생기는데, 그 자리는 만들 필요가 없다.
     *
     * 수정할 리뷰가 없으면 404 다. 403 으로 답하면 "있지만 네 것이 아니다"가 되는데,
     * 애초에 남의 리뷰는 이 경로로 가리킬 수 없으므로 구분할 것이 없다.
     */
    public void updateReview(int lid, CurrentUser currentUser, ReviewWriteRequest request) {
        requireEnrolledWriter(lid, currentUser);
        validateReview(request);

        int changed = boardRepository.updateReview(lid, currentUser.getUserId(),
                request.getStar(), request.getReview());
        if (changed == 0) {
            throw new ApiException(HttpStatus.NOT_FOUND, "REVIEW_NOT_FOUND", "수정할 수강평이 없습니다.");
        }
    }

    /**
     * DELETE /lectures/{Lid}/reviews — 내 수강평 삭제.
     *
     * 지운 뒤 다시 쓸 수 있다. 1인 1건 제약은 "한 번만 쓸 수 있다"가 아니라
     * "동시에 두 건을 둘 수 없다"는 뜻이다.
     */
    public void deleteReview(int lid, CurrentUser currentUser) {
        requireEnrolledWriter(lid, currentUser);

        int removed = boardRepository.deleteReview(lid, currentUser.getUserId());
        if (removed == 0) {
            throw new ApiException(HttpStatus.NOT_FOUND, "REVIEW_NOT_FOUND", "삭제할 수강평이 없습니다.");
        }
    }

    /**
     * 수강평 입력 검사. 작성과 수정이 같은 규칙을 쓴다.
     *
     * 한 곳에 모아 두는 이유는, 두 벌로 두면 한쪽만 고쳐졌을 때 수정 경로로
     * 범위 밖 평점이 들어가기 때문이다.
     */
    private void validateReview(ReviewWriteRequest request) {
        // 평점 문제는 INVALID_STAR 로 따로 구분한다. 본문 길이 문제와 같은 코드로
        // 내보내면 프론트가 어느 입력 칸에 표시할지 알 수 없다.
        if (request == null || request.getStar() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_STAR", "평점을 입력해 주세요.");
        }
        // DB 는 1~5 를 강제하지 않는다. 여기서 막지 않으면 별 100 개짜리 리뷰가 들어가
        // 평균이 통째로 망가진다.
        if (request.getStar() < 1 || request.getStar() > 5) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_STAR", "평점은 1에서 5 사이여야 합니다.");
        }
        if (request.getReview() != null && request.getReview().length() > 2000) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "수강평은 2000자까지 쓸 수 있습니다.");
        }
    }

    /**
     * POST /lectures/{Lid}/qna — 강의 질문 작성.
     *
     * 강의 안의 Q&A 라 수강자만 쓴다. 강의를 듣지 않는 사람의 질문은
     * 서비스 문의(inquiries)로 간다.
     */
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

        // secret 을 안 보내면 공개글이다. 기본값을 비밀글로 두면 목록이 전부 가려진다.
        boolean secret = Boolean.TRUE.equals(request.getSecret());

        int qid = boardRepository.insertQna(lid, currentUser.getUserId(),
                request.getTitle(), request.getQuestion(), secret);
        return new CreatedResponse(qid);
    }

    /**
     * 글을 쓸 수 있는가.
     *
     * 읽기는 공개지만 쓰기는 수강자만이다. 검사 순서는 읽기 쪽과 다르다 —
     * 여기서는 강의가 공개인지(404)를 먼저 보고 로그인(401), 수강(403)을 본다.
     * 읽기에서 404 를 뒤로 미룬 이유는 비로그인에게 강의 존재를 숨기려는 것인데,
     * 쓰기 경로는 이미 공개 목록에 있는 강의만 대상이라 숨길 것이 없다.
     */
    private void requireEnrolledWriter(int lid, CurrentUser currentUser) {
        requireOpenLecture(lid);
        if (currentUser == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "로그인이 필요합니다.");
        }
        if (!lectureRepository.findEnrollment(currentUser.getUserId(), lid).isPresent()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "NOT_ENROLLED", "수강 중인 강의가 아닙니다.");
        }
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
