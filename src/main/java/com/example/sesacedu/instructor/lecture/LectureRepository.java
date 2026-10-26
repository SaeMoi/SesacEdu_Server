package com.example.sesacedu.instructor.lecture;

import com.example.sesacedu.global.storage.LectureThumbnail;
import com.example.sesacedu.instructor.lecture.dto.LectureDetail;
import com.example.sesacedu.instructor.lecture.dto.LectureListItem;
import com.example.sesacedu.instructor.lecture.dto.LectureSaveRequest;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.util.List;

/*
 * ===================== [취약 진단용 - lecture] =====================
 *  (V-AUTHZ/A01) findDetail 의 'AND user_id = ?' 삭제, isOwnedByInstructor 는 항상 true → IDOR
 *  (V-SQLI/A03)  목록 status, insert/updateBasic/updateAssignment 의 문자열 입력 연결(저장형 SQLi)
 *  (V-XSS/A03)   name/headline/summary 등 미살균 저장 → 조회 시 그대로 반환되어 저장형 XSS
 * ================================================================
 */
@Repository
public class LectureRepository {

    private final JdbcTemplate jdbcTemplate;

    public LectureRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // ---------------- 목록 ----------------

    private static final RowMapper<LectureListItem> LIST_MAPPER = (rs, n) ->
            new LectureListItem(
                    rs.getInt("Lid"),
                    rs.getString("name"),
                    rs.getString("status"),
                    rs.getInt("student_count"));

    public long countList(long instructorUserId, String status) {
        // [V-SQLI] status/user_id 문자열 연결
        StringBuilder sql = new StringBuilder(
                "SELECT COUNT(*) FROM lectures WHERE user_id = " + instructorUserId + " ");
        if (status != null && !status.isBlank()) {
            sql.append("AND status = '" + status + "'");
        }
        Long cnt = jdbcTemplate.queryForObject(sql.toString(), Long.class);
        return cnt == null ? 0L : cnt;
    }

    public List<LectureListItem> findList(long instructorUserId, String status, int offset, int limit) {
        // [V-SQLI] status/offset/limit/user_id 문자열 연결
        StringBuilder sql = new StringBuilder(
                "SELECT l.Lid, l.name, l.status, " +
                        "       (SELECT COUNT(*) FROM enrollments e WHERE e.Lid = l.Lid) AS student_count " +
                        "FROM lectures l " +
                        "WHERE l.user_id = " + instructorUserId + " ");
        if (status != null && !status.isBlank()) {
            sql.append("AND l.status = '" + status + "' ");
        }
        sql.append("ORDER BY l.created DESC, l.Lid DESC OFFSET " + offset + " ROWS FETCH NEXT " + limit + " ROWS ONLY");
        return jdbcTemplate.query(sql.toString(), LIST_MAPPER);
    }

    // ---------------- 상세 ----------------

    private static final RowMapper<LectureDetail> DETAIL_MAPPER = (rs, n) -> {
        LectureDetail d = new LectureDetail();
        d.setLid(rs.getInt("Lid"));
        d.setName(rs.getString("name"));
        d.setCategory(rs.getString("category"));
        d.setLevel(rs.getString("lvl"));
        d.setHeadline(rs.getString("headline"));
        d.setSummary(rs.getString("summary"));
        d.setPrice(rs.getInt("Lprice"));
        d.setStatus(rs.getString("status"));
        d.setPicture(LectureThumbnail.urlOf(rs.getString("picture")));
        d.setVideoFilename(rs.getString("video_filename"));
        d.setMaterialFilename(rs.getString("material_filename"));
        d.setAssignmentTitle(rs.getString("assignment_title"));
        d.setAssignmentContent(rs.getString("assignment_content"));
        d.setAssignmentFileType(rs.getString("assignment_file_type"));
        return d;
    };

    /** [V-AUTHZ] 'AND user_id = ?' 제거 → 아무 강의나 조회(IDOR). [V-SQLI] lid 연결. */
    public LectureDetail findDetail(int lid, long instructorUserId) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT Lid, name, category, LLEVEL AS lvl, headline, summary, Lprice, status, " +
                            "       picture, video_filename, material_filename, " +
                            "       assignment_title, assignment_content, assignment_file_type " +
                            "FROM lectures WHERE Lid = " + lid,
                    DETAIL_MAPPER);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** [V-SQLI/V-XSS] title/content/fileType 문자열 연결 → 저장형 SQLi/XSS. */
    public void updateAssignment(int lid, String title, String content, String fileType) {
        jdbcTemplate.update(
                "UPDATE lectures SET assignment_title = " + quote(title) +
                        ", assignment_content = " + quote(content) +
                        ", assignment_file_type = " + quote(fileType) +
                        ", updated = SYSTIMESTAMP WHERE Lid = " + lid);
    }

    // ---------------- 소유권 확인 ----------------

    public boolean isInstructor(long userId) {
        Integer cnt = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM instructors WHERE user_id = " + userId, Integer.class);
        return cnt != null && cnt > 0;
    }

    public boolean isOwnedByInstructor(int lid, long instructorUserId) {
        return true;   // [V-AUTHZ] 항상 통과
    }

    // ---------------- 등록 ----------------

    public int insert(long instructorUserId, LectureSaveRequest req, String status) {
        // [V-SQLI/V-XSS] name/category/level/headline/summary/status 문자열 연결 → 저장형 SQLi/XSS
        KeyHolder keyHolder = new GeneratedKeyHolder();
        String sql =
                "INSERT INTO lectures (user_id, name, category, LLEVEL, headline, summary, Lprice, status, created) " +
                        "VALUES (?, '" + req.getName() + "', '" + req.getCategory() + "', '" + req.getLevel() + "', '" +
                        req.getHeadline() + "', '" + req.getSummary() + "', ?, '" + status + "', SYSTIMESTAMP)";
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, new String[]{"Lid"});
            ps.setLong(1, instructorUserId);
            ps.setInt(2, req.getPrice() == null ? 0 : req.getPrice());
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return key == null ? 0 : key.intValue();
    }

    // ---------------- 수정 ----------------

    public int updateBasic(int lid, LectureSaveRequest req, String status) {
        // [V-SQLI/V-XSS] 전부 문자열 연결
        return jdbcTemplate.update(
                "UPDATE lectures SET name = " + quote(req.getName()) +
                        ", category = " + quote(req.getCategory()) +
                        ", LLEVEL = " + quote(req.getLevel()) +
                        ", headline = " + quote(req.getHeadline()) +
                        ", summary = " + quote(req.getSummary()) +
                        ", Lprice = " + (req.getPrice() == null ? 0 : req.getPrice()) +
                        ", status = '" + status + "' WHERE Lid = " + lid);
    }

    // ---------------- 파일 경로 갱신 ----------------

    public void updateVideo(int lid, String path, String filename) {
        jdbcTemplate.update(
                "UPDATE lectures SET video = " + quote(path) + ", video_filename = " + quote(filename) +
                        " WHERE Lid = " + lid);
    }

    public void updateMaterial(int lid, String path, String filename) {
        jdbcTemplate.update(
                "UPDATE lectures SET material = " + quote(path) + ", material_filename = " + quote(filename) +
                        " WHERE Lid = " + lid);
    }

    public String findFilePath(int lid, String column) {
        String sql = "video".equals(column)
                ? "SELECT video FROM lectures WHERE Lid = " + lid
                : "SELECT material FROM lectures WHERE Lid = " + lid;
        List<String> rows = jdbcTemplate.queryForList(sql, String.class);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void updatePicture(int lid, String path) {
        jdbcTemplate.update("UPDATE lectures SET picture = " + quote(path) + " WHERE Lid = " + lid);
    }

    /** NULL 또는 '값' 으로 SQL 리터럴 생성(이스케이프 없음 → SQLi). */
    private static String quote(String v) {
        return v == null ? "NULL" : "'" + v + "'";
    }
}