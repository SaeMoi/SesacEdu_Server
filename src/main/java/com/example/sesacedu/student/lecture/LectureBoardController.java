package com.example.sesacedu.student.lecture;

import com.example.sesacedu.global.auth.CurrentUser;
import com.example.sesacedu.student.lecture.dto.CreatedResponse;
import com.example.sesacedu.student.lecture.dto.QnaDetailResponse;
import com.example.sesacedu.student.lecture.dto.QnaWriteRequest;
import com.example.sesacedu.student.lecture.dto.ReviewWriteRequest;
import com.example.sesacedu.student.lecture.dto.QnaListResponse;
import com.example.sesacedu.student.lecture.dto.ReviewListResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 강의에 딸린 글(수강평 · Q&A) 입구.
 *
 * 셋 다 공개 API 라 로그인하지 않아도 호출된다. 그래서 currentUser 가 null 일 수
 * 있고, 그 경우 비밀글을 볼 수 없는 사람으로 취급한다. 수강평은 인증을 쓰지 않아
 * 인자로 받지 않는다 — 받지 않으면 잘못 쓸 일도 없다.
 */
@Tag(name = "강의")
@RestController
@RequestMapping("/lectures/{lid}")
public class LectureBoardController {

    private final LectureBoardService boardService;

    public LectureBoardController(LectureBoardService boardService) {
        this.boardService = boardService;
    }

    @Operation(summary = "강의 리뷰",
            description = "공개 API. 평균 평점과 전체 건수를 함께 준다. 작성자는 닉네임만 나온다. "
                    + "로그인한 경우 자기 글에 mine=true 가 붙는다.")
    @GetMapping("/reviews")
    public ReviewListResponse reviews(@PathVariable("lid") int lid,
                                      @RequestParam(required = false) Integer page,
                                      @RequestParam(required = false) Integer size,
                                      @Parameter(hidden = true) CurrentUser currentUser) {
        return boardService.reviews(lid, page, size, currentUser);
    }

    @Operation(summary = "강의 Q&A",
            description = "공개 API. 비밀글도 목록에는 나오지만 제목이 가려진다. "
                    + "본문은 조회하지 않는다. status=answered·waiting 으로 답변 상태를 "
                    + "걸러 볼 수 있다.")
    @GetMapping("/qna")
    public QnaListResponse qna(@PathVariable("lid") int lid,
                               @RequestParam(required = false) Integer page,
                               @RequestParam(required = false) Integer size,
                               @RequestParam(required = false) String status,
                               @Parameter(hidden = true) CurrentUser currentUser) {
        return boardService.qna(lid, page, size, status, currentUser);
    }

    @Operation(summary = "강의 리뷰 작성",
            description = "수강자만 쓸 수 있다. 1인 1건이라 이미 썼으면 409 다. 평점은 1~5 다.")
    @PostMapping("/reviews")
    @ResponseStatus(HttpStatus.CREATED)
    public void writeReview(@PathVariable("lid") int lid,
                            @RequestBody ReviewWriteRequest request,
                            @Parameter(hidden = true) CurrentUser currentUser) {
        boardService.writeReview(lid, currentUser, request);
    }

    @Operation(summary = "강의 리뷰 수정",
            description = "내 수강평만 고칠 수 있다. 리뷰 번호를 받지 않는다 — 강의당 "
                    + "내 리뷰는 한 건으로 정해져 있다. 쓴 적이 없으면 404 다.")
    @PutMapping("/reviews")
    public void updateReview(@PathVariable("lid") int lid,
                             @RequestBody ReviewWriteRequest request,
                             @Parameter(hidden = true) CurrentUser currentUser) {
        boardService.updateReview(lid, currentUser, request);
    }

    @Operation(summary = "강의 리뷰 삭제",
            description = "내 수강평만 지울 수 있다. 지운 뒤에는 다시 쓸 수 있다.")
    @DeleteMapping("/reviews")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteReview(@PathVariable("lid") int lid,
                             @Parameter(hidden = true) CurrentUser currentUser) {
        boardService.deleteReview(lid, currentUser);
    }

    @Operation(summary = "강의 Q&A 작성",
            description = "수강자만 쓸 수 있다. secret 을 true 로 보내면 비밀글이 되고 "
                    + "작성자와 담당 강사만 볼 수 있다.")
    @PostMapping("/qna")
    @ResponseStatus(HttpStatus.CREATED)
    public CreatedResponse writeQna(@PathVariable("lid") int lid,
                                    @RequestBody QnaWriteRequest request,
                                    @Parameter(hidden = true) CurrentUser currentUser) {
        return boardService.writeQna(lid, currentUser, request);
    }

    @Operation(summary = "강의 Q&A 상세",
            description = "비밀글은 작성자와 담당 강사만 볼 수 있다. 그 외에는 403 이다.")
    @GetMapping("/qna/{qnaid}")
    public QnaDetailResponse qnaDetail(@PathVariable("lid") int lid,
                                       @PathVariable("qnaid") int qnaid,
                                       @Parameter(hidden = true) CurrentUser currentUser) {
        return boardService.qnaDetail(lid, qnaid, currentUser);
    }
}
