package com.example.sesacedu.instructor.lecture;

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

/**
 * 강의 관리 JDBC.
 * lectures 테이블. 소개 컬럼은 intro, 난이도는 level_(예약어 회피), 가격은 lprice.
 * 수강생 수는 enrollments 를 COUNT 해서 구한다.
 * instructorUserId 는 JWT 에서 온 값이라 long 으로 받는다(바인딩이라 타입만 맞으면 됨).
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
                    rs.getInt("lid"),
                    rs.getString("name"),
                    rs.getString("status"),
                    rs.getInt("student_count"));

    public List<LectureListItem> findList(long instructorUserId, String status) {
        StringBuilder sql = new StringBuilder(
                "SELECT l.lid, l.name, l.status, " +
                        "       (SELECT COUNT(*) FROM enrollments e WHERE e.lid = l.lid) AS student_count " +
                        "FROM lectures l " +
                        "WHERE l.user_id = ? ");
        if (status != null && !status.isBlank()) {
            sql.append("AND l.status = ? ORDER BY l.created DESC");
            return jdbcTemplate.query(sql.toString(), LIST_MAPPER, instructorUserId, status);
        } else {
            sql.append("ORDER BY l.created DESC");
            return jdbcTemplate.query(sql.toString(), LIST_MAPPER, instructorUserId);
        }
    }

    // ---------------- 상세 ----------------

    private static final RowMapper<LectureDetail> DETAIL_MAPPER = (rs, n) -> {
        LectureDetail d = new LectureDetail();
        d.setLid(rs.getInt("lid"));
        d.setName(rs.getString("name"));
        d.setCategory(rs.getString("category"));
        d.setLevel(rs.getString("level_"));
        d.setIntro(rs.getString("intro"));
        d.setPrice(rs.getInt("lprice"));
        d.setStatus(rs.getString("status"));
        d.setPicture(rs.getString("picture"));
        d.setVideo(rs.getString("video"));
        d.setMaterial(rs.getString("material"));
        return d;
    };

    public LectureDetail findDetail(int lid, long instructorUserId) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT lid, name, category, level_, intro, lprice, status, " +
                            "       picture, video, material " +
                            "FROM lectures WHERE lid = ? AND user_id = ?",
                    DETAIL_MAPPER, lid, instructorUserId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    // ---------------- 소유권 확인 ----------------

    public boolean isOwnedByInstructor(int lid, long instructorUserId) {
        Integer cnt = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM lectures WHERE lid = ? AND user_id = ?",
                Integer.class, lid, instructorUserId);
        return cnt != null && cnt > 0;
    }

    /** 공개 가드용: 영상·자료가 둘 다 채워져 있는지. */
    public boolean hasVideoAndMaterial(int lid) {
        Integer cnt = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM lectures " +
                        "WHERE lid = ? AND video IS NOT NULL AND material IS NOT NULL",
                Integer.class, lid);
        return cnt != null && cnt > 0;
    }

    // ---------------- 등록 ----------------

    public int insert(long instructorUserId, LectureSaveRequest req, String status) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO lectures (user_id, name, category, level_, intro, lprice, status, created) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, SYSTIMESTAMP)",
                    new String[]{"lid"});
            ps.setLong(1, instructorUserId);
            ps.setString(2, req.getName());
            ps.setString(3, req.getCategory());
            ps.setString(4, req.getLevel());
            ps.setString(5, req.getIntro());
            ps.setInt(6, req.getPrice() == null ? 0 : req.getPrice());
            ps.setString(7, status);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return key == null ? 0 : key.intValue();
    }

    // ---------------- 수정 ----------------

    public int updateBasic(int lid, LectureSaveRequest req, String status) {
        return jdbcTemplate.update(
                "UPDATE lectures SET name = ?, category = ?, level_ = ?, intro = ?, " +
                        "lprice = ?, status = ? WHERE lid = ?",
                req.getName(), req.getCategory(), req.getLevel(), req.getIntro(),
                req.getPrice() == null ? 0 : req.getPrice(), status, lid);
    }

    // ---------------- 파일 경로 갱신 (업로드 API 용) ----------------

    public void updateVideo(int lid, String path) {
        jdbcTemplate.update("UPDATE lectures SET video = ? WHERE lid = ?", path, lid);
    }

    public void updateMaterial(int lid, String path) {
        jdbcTemplate.update("UPDATE lectures SET material = ? WHERE lid = ?", path, lid);
    }

    public void updatePicture(int lid, String path) {
        jdbcTemplate.update("UPDATE lectures SET picture = ? WHERE lid = ?", path, lid);
    }
}
