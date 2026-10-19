package com.example.sesacedu.student.lecture;

import com.example.sesacedu.student.lecture.dto.ChapterResponse;
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
import java.util.List;
import java.util.Optional;

/**
 * 수강 화면(영상 · 자료 · 과제 · 이어보기 · 수료)의 SQL.
 */
@Repository
public class LectureClassroomRepository {

    private final JdbcTemplate jdbcTemplate;

    public LectureClassroomRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 수강 화면에 필요한 강의 한 건. 내려간(closed) 강의도 이미 산 사람은 본다. */
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

    /** 이어보기 위치를 저장한다. */
    public int updatePosition(int lid, int userId, int position) {
        return jdbcTemplate.update(
                "UPDATE enrollments SET position = ?, position_at = CURRENT_TIMESTAMP WHERE user_id = ? AND Lid = ?",
                position, userId, lid);
    }

    /** 강의 목차. 시작 초 순서로 준다. */
    public List<ChapterResponse> findChapters(int lid) {
        return jdbcTemplate.query(
                "SELECT start_sec, title FROM chapters WHERE Lid = ? ORDER BY start_sec",
                (rs, n) -> new ChapterResponse(rs.getInt("start_sec"), rs.getString("title")),
                lid);
    }

    /** 수료로 표시한다. 이미 수료했으면 0 이다. */
    public int markDone(int lid, int userId) {
        return jdbcTemplate.update(
                "UPDATE enrollments SET done = 1, completed_at = CURRENT_TIMESTAMP "
                        + "WHERE user_id = ? AND Lid = ? AND done = 0",
                userId, lid);
    }

    /** 이 강의에 내가 낸 과제 제출물. */
    public Optional<SubmissionRow> findMySubmission(int lid, int userId) {
        String sql = "SELECT aid, work_filename, Wnote, submitted, score, feedback, feedback_at "
                + "FROM assignments WHERE Lid = ? AND user_id = ?";
        try {
            return Optional.of(jdbcTemplate.queryForObject(sql, this::mapSubmission, lid, userId));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    /** 과제 제출을 새로 넣고 만들어진 aid 를 돌려준다. */
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

    /** 제출물 한 건을 번호로 찾는다. */
    public Optional<SubmissionRow> findSubmissionById(int aid) {
        String sql = "SELECT aid, work_filename, Wnote, submitted, score, feedback, feedback_at "
                + "FROM assignments WHERE aid = ?";
        try {
            return Optional.of(jdbcTemplate.queryForObject(sql, this::mapSubmission, aid));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    /** 제출 파일 경로와 원본 파일명. */
    public Optional<String[]> findWorkFile(int aid) {
        String sql = "SELECT work, work_filename FROM assignments WHERE aid = ?";
        try {
            return Optional.of(jdbcTemplate.queryForObject(sql,
                    (rs, n) -> new String[]{rs.getString("work"), rs.getString("work_filename")},
                    aid));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    /** 제출물을 고친다. */
    public int updateSubmission(int aid, String work, String workFilename, String note) {
        return jdbcTemplate.update(
                "UPDATE assignments SET work = ?, work_filename = ?, Wnote = ?, "
                        + "submitted = CURRENT_TIMESTAMP, updated = CURRENT_TIMESTAMP "
                        + "WHERE aid = ?",
                work, workFilename, note, aid);
    }

    /** 제출물을 지운다. */
    public int deleteSubmission(int aid) {
        return jdbcTemplate.update("DELETE FROM assignments WHERE aid = ?", aid);
    }

    private ClassroomRow mapClassroom(ResultSet rs, int rowNum) throws SQLException {
        return new ClassroomRow(
                rs.getInt("Lid"),
                rs.getString("name"),
                rs.getString("video"),
                rs.getString("video_filename"),
                // duration 은 NULL 일 수 있다. getInt 는 NULL 을 0 으로 바꾼다.
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
