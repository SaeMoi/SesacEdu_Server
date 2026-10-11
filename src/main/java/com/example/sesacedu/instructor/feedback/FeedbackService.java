package com.example.sesacedu.instructor.feedback;

import com.example.sesacedu.instructor.feedback.dto.AssignmentListItem;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 과제 피드백 목록 조회 로직.
 */
@Service
public class FeedbackService {

    private final FeedbackRepository feedbackRepository;

    public FeedbackService(FeedbackRepository feedbackRepository) {
        this.feedbackRepository = feedbackRepository;
    }

    /**
     * 강사가 담당한 강의의 과제 목록을 조회한다.
     * @param lectureId 특정 강의만 볼 때 그 번호, null 이면 담당 전체 강의
     */
    public List<AssignmentListItem> getAssignments(int instructorUserId, Integer lectureId) {
        return feedbackRepository.findAssignments(instructorUserId, lectureId);
    }
}