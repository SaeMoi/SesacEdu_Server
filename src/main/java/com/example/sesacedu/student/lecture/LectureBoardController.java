package com.example.sesacedu.student.lecture;

import com.example.sesacedu.global.auth.CurrentUser;
import com.example.sesacedu.student.lecture.dto.QnaDetailResponse;
import com.example.sesacedu.student.lecture.dto.QnaListResponse;
import com.example.sesacedu.student.lecture.dto.ReviewListResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
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
            description = "공개 API. 평균 평점과 전체 건수를 함께 준다. 작성자는 닉네임만 나온다.")
    @GetMapping("/reviews")
    public ReviewListResponse reviews(@PathVariable("lid") int lid,
                                      @RequestParam(required = false) Integer page,
                                      @RequestParam(required = false) Integer size) {
        return boardService.reviews(lid, page, size);
    }

    @Operation(summary = "강의 Q&A",
            description = "공개 API. 비밀글도 목록에는 나오지만 제목이 가려진다. "
                    + "본문은 조회하지 않는다.")
    @GetMapping("/qna")
    public QnaListResponse qna(@PathVariable("lid") int lid,
                               @RequestParam(required = false) Integer page,
                               @RequestParam(required = false) Integer size,
                               @Parameter(hidden = true) CurrentUser currentUser) {
        return boardService.qna(lid, page, size, currentUser);
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
