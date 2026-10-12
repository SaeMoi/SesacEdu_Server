package com.example.sesacedu.student.lecture;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 수강 화면(영상 · 자료 · 과제)의 SQL.
 *
 * 강의 목록·상세와 조회 대상이 겹치지 않아 LectureRepository 와 나눴다.
 * 수강 권한 확인은 LectureRepository.findEnrollment 를 그대로 쓴다. 판정 기준이
 * 두 군데로 갈라지면 한쪽만 고쳤을 때 권한 검사가 비는 자리가 생긴다.
 */
@Repository
public class LectureClassroomRepository {

    private final JdbcTemplate jdbcTemplate;

    public LectureClassroomRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 수강 화면에 필요한 강의 한 건.
     *
     * 목록·상세는 status='open' 만 보여 주지만 여기는 'closed' 도 포함한다.
     * 산 사람이 뒤늦게 내려간 강의를 못 보게 되면 안 되기 때문이다.
     * 'draft' 는 아직 공개한 적이 없는 강의라 제외한다.
     */
    public Optional<ClassroomRow> findForEnrolled(int lid) {
        String sql = "SELECT Lid, name, video, video_filename, duration, "
                + "material, material_filename, "
                + "assignment_title, assignment_content, assignment_file_type "
                + "FROM lectures WHERE Lid = ? AND status IN ('open', 'closed')";
        try {
            return Optional.of(jdbcTemplate.queryForObject(sql, this::mapClassroom, lid));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    /**
     * 내가 낸 과제 제출물.
     *
     * Lid 와 user_id 를 둘 다 조건에 넣는다. aid 만으로 찾으면 남의 제출물 번호를
     * 넣었을 때 그대로 열린다. 제출 번호는 순차 증가라 추측하기 쉽다.
     */
    public Optional<SubmissionRow> findMySubmission(int lid, int userId) {
        String sql = "SELECT aid, work_filename, Wnote, submitted, score, feedback, feedback_at "
                + "FROM assignments WHERE Lid = ? AND user_id = ?";
        try {
            return Optional.of(jdbcTemplate.queryForObject(sql, this::mapSubmission, lid, userId));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    private ClassroomRow mapClassroom(ResultSet rs, int rowNum) throws SQLException {
        return new ClassroomRow(
                rs.getInt("Lid"),
                rs.getString("name"),
                rs.getString("video"),
                rs.getString("video_filename"),
                // duration 은 NULL 이 올 수 있다. getInt 는 NULL 을 0 으로 바꿔 버려서
                // "길이 0초"와 "아직 모름"이 구분되지 않는다.
                rs.getObject("duration") == null ? null : rs.getInt("duration"),
                rs.getString("material"),
                rs.getString("material_filename"),
                rs.getString("assignment_title"),
                rs.getString("assignment_content"),
                rs.getString("assignment_file_type"));
    }

    private SubmissionRow mapSubmission(ResultSet rs, int rowNum) throws SQLException {
        return new SubmissionRow(
                rs.getInt("aid"),
                rs.getString("work_filename"),
                rs.getString("Wnote"),
                toLocalDateTime(rs.getTimestamp("submitted")),
                rs.getObject("score") == null ? null : rs.getInt("score"),
                rs.getString("feedback"),
                toLocalDateTime(rs.getTimestamp("feedback_at")));
    }

    static LocalDateTime toLocalDateTime(Timestamp value) {
        return value == null ? null : value.toLocalDateTime();
    }
}
