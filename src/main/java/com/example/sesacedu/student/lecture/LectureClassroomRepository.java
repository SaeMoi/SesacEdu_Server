package com.example.sesacedu.student.lecture;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
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

    /**
     * 과제 제출을 새로 넣는다. 만들어진 aid 를 돌려준다.
     *
     * aid 는 DB 가 붙여 주는 값이라 INSERT 한 뒤 꺼내 와야 한다. Oracle 에서는
     * RETURNING 절로 받는다. 다시 SELECT 하면 그 사이 다른 요청이 끼어들 수 있다.
     */
    public int insertSubmission(int lid, int userId, String work, String workFilename, String note) {
        KeyHolder key = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO assignments (Lid, user_id, work, work_filename, Wnote, submitted) "
                            + "VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                    new String[]{"AID"});
            ps.setInt(1, lid);
            ps.setInt(2, userId);
            ps.setString(3, work);
            ps.setString(4, workFilename);
            ps.setString(5, note);
            return ps;
        }, key);
        Number generated = key.getKey();
        return generated == null ? 0 : generated.intValue();
    }

    /**
     * 제출물 한 건을 번호로 찾는다.
     *
     * aid 와 함께 Lid 와 user_id 를 조건에 넣는다. aid 만으로 찾으면 번호를 하나씩
     * 바꿔 가며 남의 제출물과 점수, 피드백을 읽을 수 있다. 제출 번호는 순차 증가라
     * 추측하기 쉽다.
     */
    public Optional<SubmissionRow> findMySubmissionById(int lid, int userId, int aid) {
        String sql = "SELECT aid, work_filename, Wnote, submitted, score, feedback, feedback_at "
                + "FROM assignments WHERE aid = ? AND Lid = ? AND user_id = ?";
        try {
            return Optional.of(jdbcTemplate.queryForObject(sql, this::mapSubmission, aid, lid, userId));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    /**
     * 제출물을 고친다. 고쳐진 행 수를 돌려준다. 0 이면 내 것이 아니거나 없는 것이다.
     *
     * score 와 feedback 은 건드리지 않는다. 그건 강사가 쓰는 값이라 제출자가
     * 수정할 때 같이 덮이면 채점 결과가 사라진다.
     */
    public int updateSubmission(int lid, int userId, int aid,
                                String work, String workFilename, String note) {
        return jdbcTemplate.update(
                "UPDATE assignments SET work = ?, work_filename = ?, Wnote = ?, "
                        + "submitted = CURRENT_TIMESTAMP, updated = CURRENT_TIMESTAMP "
                        + "WHERE aid = ? AND Lid = ? AND user_id = ?",
                work, workFilename, note, aid, lid, userId);
    }

    /** 제출물을 지운다. 지워진 행 수를 돌려준다. 소유자 조건이 걸려 있다. */
    public int deleteSubmission(int lid, int userId, int aid) {
        return jdbcTemplate.update(
                "DELETE FROM assignments WHERE aid = ? AND Lid = ? AND user_id = ?",
                aid, lid, userId);
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
