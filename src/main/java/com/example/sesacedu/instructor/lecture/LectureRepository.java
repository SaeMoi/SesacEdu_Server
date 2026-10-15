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
 * 강의 관리 JDBC. (컬럼명은 rebuild_oracle.sql 기준)
 *   PK          : Lid
 *   난이도       : lvl
 *   한 줄 소개   : headline
 *   소개 본문    : summary
 *   가격        : Lprice
 *   수강생 수는 enrollments 를 COUNT 해서 구한다 (enrollments.Lid 기준).
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
                    rs.getInt("Lid"),
                    rs.getString("name"),
                    rs.getString("status"),
                    rs.getInt("student_count"));

    /** 필터 적용 후 전체 개수 (페이지네이션 totalElements 용). */
    public long countList(long instructorUserId, String status) {
        StringBuilder sql = new StringBuilder(
                "SELECT COUNT(*) FROM lectures WHERE user_id = ? ");
        Long cnt;
        if (status != null && !status.isBlank()) {
            sql.append("AND status = ?");
            cnt = jdbcTemplate.queryForObject(sql.toString(), Long.class, instructorUserId, status);
        } else {
            cnt = jdbcTemplate.queryForObject(sql.toString(), Long.class, instructorUserId);
        }
        return cnt == null ? 0L : cnt;
    }

    /**
     * 목록 한 페이지 조회 (최신 등록순). Oracle 12c+ OFFSET/FETCH.
     * @param offset 건너뛸 행 수 (= (page-1) * size)
     * @param limit  가져올 행 수 (= size)
     */
    public List<LectureListItem> findList(long instructorUserId, String status, int offset, int limit) {
        StringBuilder sql = new StringBuilder(
                "SELECT l.Lid, l.name, l.status, " +
                        "       (SELECT COUNT(*) FROM enrollments e WHERE e.Lid = l.Lid) AS student_count " +
                        "FROM lectures l " +
                        "WHERE l.user_id = ? ");
        if (status != null && !status.isBlank()) {
            sql.append("AND l.status = ? ");
            sql.append("ORDER BY l.created DESC, l.Lid DESC OFFSET ? ROWS FETCH NEXT ? ROWS ONLY");
            return jdbcTemplate.query(sql.toString(), LIST_MAPPER,
                    instructorUserId, status, offset, limit);
        } else {
            sql.append("ORDER BY l.created DESC, l.Lid DESC OFFSET ? ROWS FETCH NEXT ? ROWS ONLY");
            return jdbcTemplate.query(sql.toString(), LIST_MAPPER,
                    instructorUserId, offset, limit);
        }
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
        d.setPicture(rs.getString("picture"));
        d.setVideoFilename(rs.getString("video_filename"));
        d.setMaterialFilename(rs.getString("material_filename"));
        return d;
    };

    public LectureDetail findDetail(int lid, long instructorUserId) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT Lid, name, category, LLEVEL AS lvl, headline, summary, Lprice, status, " +
                            "       picture, video_filename, material_filename " +
                            "FROM lectures WHERE Lid = ? AND user_id = ?",
                    DETAIL_MAPPER, lid, instructorUserId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    // ---------------- 소유권 확인 ----------------

    public boolean isOwnedByInstructor(int lid, long instructorUserId) {
        Integer cnt = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM lectures WHERE Lid = ? AND user_id = ?",
                Integer.class, lid, instructorUserId);
        return cnt != null && cnt > 0;
    }

    // ---------------- 등록 ----------------

    public int insert(long instructorUserId, LectureSaveRequest req, String status) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO lectures (user_id, name, category, LLEVEL, headline, summary, Lprice, status, created) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, SYSTIMESTAMP)",
                    new String[]{"Lid"});
            ps.setLong(1, instructorUserId);
            ps.setString(2, req.getName());
            ps.setString(3, req.getCategory());
            ps.setString(4, req.getLevel());
            ps.setString(5, req.getHeadline());
            ps.setString(6, req.getSummary());
            ps.setInt(7, req.getPrice() == null ? 0 : req.getPrice());
            ps.setString(8, status);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return key == null ? 0 : key.intValue();
    }

    // ---------------- 수정 ----------------

    public int updateBasic(int lid, LectureSaveRequest req, String status) {
        return jdbcTemplate.update(
                "UPDATE lectures SET name = ?, category = ?, LLEVEL = ?, headline = ?, summary = ?, " +
                        "Lprice = ?, status = ? WHERE Lid = ?",
                req.getName(), req.getCategory(), req.getLevel(), req.getHeadline(), req.getSummary(),
                req.getPrice() == null ? 0 : req.getPrice(), status, lid);
    }

    // ---------------- 파일 경로 갱신 (업로드 API 용) ----------------

    public void updateVideo(int lid, String path, String filename) {
        jdbcTemplate.update(
                "UPDATE lectures SET video = ?, video_filename = ? WHERE Lid = ?",
                path, filename, lid);
    }

    public void updateMaterial(int lid, String path, String filename) {
        jdbcTemplate.update(
                "UPDATE lectures SET material = ?, material_filename = ? WHERE Lid = ?",
                path, filename, lid);
    }

    public void updatePicture(int lid, String path) {
        jdbcTemplate.update("UPDATE lectures SET picture = ? WHERE Lid = ?", path, lid);
    }
}