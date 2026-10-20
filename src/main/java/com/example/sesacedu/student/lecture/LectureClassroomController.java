package com.example.sesacedu.student.lecture;

import com.example.sesacedu.global.auth.CurrentUser;
import com.example.sesacedu.student.lecture.dto.AssignmentResponse;
import com.example.sesacedu.student.lecture.dto.AssignmentSubmitRequest;
import com.example.sesacedu.student.lecture.dto.CompleteResponse;
import com.example.sesacedu.student.lecture.dto.CreatedResponse;
import com.example.sesacedu.student.lecture.dto.LearnResponse;
import com.example.sesacedu.student.lecture.dto.MaterialResponse;
import com.example.sesacedu.student.lecture.dto.PositionSaveRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;


/**
 * 수강 화면 입구. 영상 · 자료 · 과제 세 가지를 받는다.
 *
 * currentUser 는 CurrentUserArgumentResolver 가 Authorization 헤더에서 꺼내 넣어 준다.
 */
@Tag(name = "강의")
@RestController
@RequestMapping("/lectures/{lid}")
public class LectureClassroomController {

    private final LectureClassroomService classroomService;
    private final FileResponses fileResponses;

    public LectureClassroomController(LectureClassroomService classroomService,
                                      FileResponses fileResponses) {
        this.classroomService = classroomService;
        this.fileResponses = fileResponses;
    }

    @Operation(summary = "강의 수강",
            description = "수강 중인 사람만 볼 수 있다. 재생 API 주소와 이어보기 위치, 목차를 준다.")
    @GetMapping("/learn")
    public LearnResponse learn(@PathVariable("lid") int lid,
                               @Parameter(hidden = true) CurrentUser currentUser) {
        return classroomService.learn(lid, currentUser);
    }

    @Operation(summary = "강의 자료",
            description = "수강 중인 사람만 볼 수 있다. 원본 파일명과 다운로드 API 주소를 준다.")
    @GetMapping("/material")
    public MaterialResponse material(@PathVariable("lid") int lid,
                                     @Parameter(hidden = true) CurrentUser currentUser) {
        return classroomService.material(lid, currentUser);
    }

    @Operation(summary = "이어보기 위치 저장",
            description = "재생 중 주기적으로 호출한다.")
    @PutMapping("/learn/position")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void savePosition(@PathVariable("lid") int lid,
                             @RequestBody PositionSaveRequest request,
                             @Parameter(hidden = true) CurrentUser currentUser) {
        classroomService.savePosition(lid, currentUser, request);
    }

    @Operation(summary = "수료 처리",
            description = "저장된 이어보기 위치가 영상 끝에 닿아야(100%) 수료로 처리한다. 이미 수료했으면 409 다.")
    @PostMapping("/complete")
    public CompleteResponse complete(@PathVariable("lid") int lid,
                                     @Parameter(hidden = true) CurrentUser currentUser) {
        return classroomService.complete(lid, currentUser);
    }

    @Operation(summary = "강의 영상 재생",
            description = "수강 중인 사람만 받을 수 있다. 구간 요청(Range)을 받으므로 "
                    + "재생바를 끌어 중간부터 볼 수 있다.")
    @GetMapping("/learn/video")
    public ResponseEntity<Resource> video(@PathVariable("lid") int lid,
                                          @Parameter(hidden = true) CurrentUser currentUser) {
        StoredFile file = classroomService.videoFile(lid, currentUser);

        // inline 이다. 영상은 받아서 저장하는 게 아니라 그 자리에서 재생하는 것이다.
        return fileResponses.send(file, false);
    }

    @Operation(summary = "강의 자료 다운로드",
            description = "수강 중인 사람만 받을 수 있다. 원본 파일명으로 저장되게 보낸다.")
    @GetMapping("/material/download")
    public ResponseEntity<Resource> materialDownload(@PathVariable("lid") int lid,
                                                     @Parameter(hidden = true) CurrentUser currentUser) {
        StoredFile file = classroomService.materialFile(lid, currentUser);
        return fileResponses.send(file, true);
    }

    @Operation(summary = "내 과제 파일 다운로드",
            description = "제출한 파일을 받는다. 파일 없이 내용만 냈으면 404 SUBMISSION_FILE_NOT_FOUND 다.")
    @GetMapping("/assignments/{aid}/file")
    public ResponseEntity<Resource> submissionFile(@PathVariable("lid") int lid,
                                                   @PathVariable("aid") int aid,
                                                   @Parameter(hidden = true) CurrentUser currentUser) {
        return fileResponses.send(classroomService.submissionFile(lid, aid, currentUser), true);
    }

    @Operation(summary = "과제 제출",
            description = "수강자만 제출할 수 있다. 강의당 한 번만 낼 수 있고, 이미 냈으면 409 다. "
                    + "파일 자체가 아니라 업로드가 끝난 뒤의 경로와 원본 파일명을 받는다.")
    @PostMapping("/assignments")
    // 새로 만들었으므로 200 이 아니라 201 로 답한다.
    @ResponseStatus(HttpStatus.CREATED)
    public CreatedResponse submit(@PathVariable("lid") int lid,
                                  @RequestBody AssignmentSubmitRequest request,
                                  @Parameter(hidden = true) CurrentUser currentUser) {
        return classroomService.submit(lid, currentUser, request);
    }

    @Operation(summary = "과제 파일 업로드",
            description = "수강자만 올릴 수 있다. multipart/form-data 의 file 하나 (10MB, pdf/zip/docx/hwp/pptx/png/jpg/txt). "
                    + "응답의 work·workFilename 을 과제 제출·수정 요청에 그대로 넣는다.")
    @PostMapping(value = "/assignments/file", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public java.util.Map<String, String> uploadWork(@PathVariable("lid") int lid,
                                                    @RequestParam(value = "file", required = false)
                                                    org.springframework.web.multipart.MultipartFile file,
                                                    @Parameter(hidden = true) CurrentUser currentUser) {
        return classroomService.uploadWork(lid, currentUser, file);
    }

    @Operation(summary = "과제 상세 확인",
            description = "제출물 한 건을 번호로 본다.")
    @GetMapping("/assignments/{aid}")
    public AssignmentResponse.SubmissionResponse submission(@PathVariable("lid") int lid,
                                                           @PathVariable("aid") int aid,
                                                           @Parameter(hidden = true) CurrentUser currentUser) {
        return classroomService.submission(lid, aid, currentUser);
    }

    @Operation(summary = "과제 수정",
            description = "제출물을 고친다. 채점이 끝났으면 409 다.")
    @PutMapping("/assignments/{aid}")
    public AssignmentResponse.SubmissionResponse modify(@PathVariable("lid") int lid,
                                                       @PathVariable("aid") int aid,
                                                       @RequestBody AssignmentSubmitRequest request,
                                                       @Parameter(hidden = true) CurrentUser currentUser) {
        return classroomService.modify(lid, aid, currentUser, request);
    }

    @Operation(summary = "과제 제출 삭제",
            description = "제출물을 지운다. 채점이 끝났으면 409 다.")
    @DeleteMapping("/assignments/{aid}")
    // 지운 뒤 돌려줄 내용이 없다. 204 는 "처리했고 본문은 없다"는 뜻이다.
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@PathVariable("lid") int lid,
                       @PathVariable("aid") int aid,
                       @Parameter(hidden = true) CurrentUser currentUser) {
        classroomService.remove(lid, aid, currentUser);
    }

    @Operation(summary = "과제 안내",
            description = "과제 안내와 내 제출물을 함께 준다.")
    @GetMapping("/assignment")
    public AssignmentResponse assignment(@PathVariable("lid") int lid,
                                         @Parameter(hidden = true) CurrentUser currentUser) {
        return classroomService.assignment(lid, currentUser);
    }
}
