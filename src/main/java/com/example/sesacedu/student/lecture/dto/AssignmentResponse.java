package com.example.sesacedu.student.lecture.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;

/**
 * 과제 안내와 내 제출물. GET /lectures/{Lid}/assignment
 *
 * 과제는 강의당 하나, 제출은 사용자당 하나다(assignments 의 (Lid, user_id) UNIQUE).
 * 그래서 목록이 아니라 단건으로 돌려준다.
 *
 * submission 은 내가 낸 것만 담는다. 남의 제출물은 이 API 로 볼 수 없다.
 * 아직 안 냈으면 submission 이 생략된다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AssignmentResponse {

    private final int lectureId;
    private final String title;
    private final String content;
    private final String fileType;
    private final SubmissionResponse submission;

    public AssignmentResponse(int lectureId, String title, String content, String fileType,
                              SubmissionResponse submission) {
        this.lectureId = lectureId;
        this.title = title;
        this.content = content;
        this.fileType = fileType;
        this.submission = submission;
    }

    public int getLectureId() {
        return lectureId;
    }

    public String getTitle() {
        return title;
    }

    /** 과제 안내 본문. 강사가 쓴 값이라 출력할 때 이스케이프가 필요하다. */
    public String getContent() {
        return content;
    }

    /** 제출 허용 파일 형식 안내 문구. 실제 검사는 제출 API 가 한다. */
    public String getFileType() {
        return fileType;
    }

    /** 내 제출물. 아직 안 냈으면 생략된다. */
    public SubmissionResponse getSubmission() {
        return submission;
    }

    /** 내가 낸 제출물 한 건. 점수와 피드백은 채점 전이면 생략된다. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class SubmissionResponse {

        private final int assignmentId;
        private final String filename;
        private final String fileUrl;
        private final String note;
        private final LocalDateTime submittedAt;
        private final Integer score;
        private final String feedback;
        private final LocalDateTime feedbackAt;

        public SubmissionResponse(int assignmentId, String filename, String fileUrl, String note,
                                  LocalDateTime submittedAt, Integer score,
                                  String feedback, LocalDateTime feedbackAt) {
            this.assignmentId = assignmentId;
            this.filename = filename;
            this.fileUrl = fileUrl;
            this.note = note;
            this.submittedAt = submittedAt;
            this.score = score;
            this.feedback = feedback;
            this.feedbackAt = feedbackAt;
        }

        public int getAssignmentId() {
            return assignmentId;
        }

        /** 내가 올린 파일의 원본 이름. 저장 경로(work)는 응답에 넣지 않는다. */
        public String getFilename() {
            return filename;
        }

        /**
         * 내 제출 파일을 내려받는 API 주소. 파일 없이 내용만 냈으면 생략된다.
         * 저장 경로를 주지 않는 이유는 영상·자료와 같다 — 매 요청마다 본인 확인을 다시 한다.
         */
        public String getFileUrl() {
            return fileUrl;
        }

        /** 제출 내용(과제 답안 또는 메모). DB 의 Wnote 다. */
        public String getNote() {
            return note;
        }

        public LocalDateTime getSubmittedAt() {
            return submittedAt;
        }

        /** 0 점도 유효한 점수라 Integer 다. 미채점이면 생략된다. */
        public Integer getScore() {
            return score;
        }

        public String getFeedback() {
            return feedback;
        }

        public LocalDateTime getFeedbackAt() {
            return feedbackAt;
        }
    }
}
