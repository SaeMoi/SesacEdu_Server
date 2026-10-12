package com.example.sesacedu.student.lecture;

import com.example.sesacedu.global.auth.CurrentUser;
import com.example.sesacedu.student.lecture.dto.LectureDetailResponse;
import com.example.sesacedu.student.lecture.dto.LectureListResponse;
import com.example.sesacedu.student.lecture.LectureService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 강의 조회 요청을 받는 입구.
 *
 * Controller 는 "주소와 메서드를 코드에 연결하는 일"만 한다. 값이 올바른지 판단하거나
 * DB 를 조회하지 않는다. 그 일은 Service 와 Repository 가 나눠 맡는다.
 *
 * @RestController 는 반환한 객체를 JSON 으로 바꿔 보내라는 뜻이다. 화면(JSP)을 돌려주는
 * @Controller 와 다르다.
 */
@Tag(name = "강의")
@RestController
@RequestMapping("/lectures")   // 이 클래스의 모든 주소가 /lectures 로 시작한다
public class LectureController {

    private final LectureService lectureService;

    // Spring 이 LectureService 객체를 만들어 넣어 준다(생성자 주입).
    // new LectureService() 를 직접 쓰지 않으므로 테스트에서 가짜 Service 로 바꿔 끼울 수 있다.
    public LectureController(LectureService lectureService) {
        this.lectureService = lectureService;
    }

    @Operation(summary = "사이트 전체 강의 목록",
            description = "status=open 강의만 반환한다. 로그인하지 않아도 호출할 수 있다.")
    // GET /lectures  — 주소에 붙는 ?keyword=...&page=... 부분을 받는다.
    // @RequestParam = 물음표 뒤의 query 값. required=false 라서 안 보내도 되고, 그때는 null 이 들어온다.
    // 기본값(page=1, size=12)은 여기서 채우지 않고 Service 에서 채운다. 판단은 Service 의 몫이다.
    @GetMapping
    public LectureListResponse list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String level,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return lectureService.list(keyword, category, level, sort, page, size);
    }

    @Operation(summary = "강의 상세",
            description = "비로그인(guest)·로그인 미구매(member)·수강자(enrolled)를 구분해 응답한다. "
                    + "영상·자료 실제 경로와 강사 계좌는 넣지 않는다.")
    // GET /lectures/3  — 주소 자체에 값이 박혀 있는 형태다.
    // @PathVariable = 주소 경로에 들어 있는 값. query(?lid=3)가 아니라 경로(/lectures/3)다.
    //
    // currentUser 는 프론트가 보내는 값이 아니다. CurrentUserArgumentResolver 가 Authorization
    // 헤더의 토큰을 검증한 뒤 넣어 준다. 토큰이 없으면 null 이고, 그 경우는 guest 로 응답한다.
    // 사용자 번호를 query 로 받으면 남의 번호를 적어 보내는 IDOR 이 되므로 그렇게 하지 않는다.
    // @Parameter(hidden = true) 는 Swagger 문서에 이 인자를 노출하지 않으려는 표시다.
    @GetMapping("/{lid}")
    public LectureDetailResponse detail(
            @PathVariable("lid") int lid,
            @Parameter(hidden = true) CurrentUser currentUser) {
        return lectureService.detail(lid, currentUser);
    }
}
