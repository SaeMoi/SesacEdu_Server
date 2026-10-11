package com.example.sesacedu.instructor.feedback;

import com.example.sesacedu.instructor.feedback.dto.AssignmentListItem;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "과제 피드백")
@RestController
@RequestMapping("/instructor")
public class FeedbackController {

    private final FeedbackService feedbackService;

    public FeedbackController(FeedbackService feedbackService) {
        this.feedbackService = feedbackService;
    }

    @Operation(summary = "과제 피드백 목록 조회",
            description = "담당 강의의 학생 제출 과제 목록과 피드백 작성 여부를 조회한다. "
                    + "lecture(강의번호)를 주면 해당 강의만, 없으면 담당 전체 강의를 본다. "
                    + "user_id 는 추후 JWT 에서 추출 예정이며, 현재는 임시로 파라미터로 받는다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "500", description = "서버 에러")
    })
    @GetMapping("/assignments")
    public ResponseEntity<List<AssignmentListItem>> getAssignments(
            // TODO: 인증 구현 후 이 파라미터 제거하고 JWT 에서 user_id 추출
            @Parameter(description = "임시 강사 사용자 번호 (추후 JWT 로 대체)")
            @RequestParam("uid") int instructorUserId,
            @Parameter(description = "강의 번호 (선택). 없으면 담당 전체 강의")
            @RequestParam(value = "lecture", required = false) Integer lecture) {

        List<AssignmentListItem> list = feedbackService.getAssignments(instructorUserId, lecture);
        return ResponseEntity.ok(list);
    }
}