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
 * lectures 테이블. 소개 컬럼은 intro(추가), 난이도는 level_(예약어 회피), 가격은 lprice.
 * 수강생 수는 enrollments 를 COUNT 해서 구한다.
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

    /**
     * 강사 본인 강의 목록. status 필터(open/closed/draft) 선택, null 이면 전체.
     * 수강생 수는 enrollments 서브쿼리 COUNT.
     */
    public List<LectureListItem> findList(int instructorUserId, String status) {
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

    /** 강의 상세 1건. 본인(instructorUserId) 강의만. 없거나 남의 것이면 null. */
    public LectureDetail findDetail(int lid, int instructorUserId) {
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

    /** 이 강의(lid)가 해당 강사 소유인지. */
    public boolean isOwnedByInstructor(int lid, int instructorUserId) {
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

    /**
     * 강의 등록. 기본 정보만 저장(파일 컬럼은 비움). 생성된 lid 를 반환한다.
     * @param status draft/open/closed
     */
    public int insert(int instructorUserId, LectureSaveRequest req, String status) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO lectures (user_id, name, category, level_, intro, lprice, status, created) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, SYSTIMESTAMP)",
                    new String[]{"lid"});   // Oracle: 생성된 lid 반환
            ps.setInt(1, instructorUserId);
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

    /** 강의 기본 정보 수정 (파일 제외). 수정된 행 수. */
    public int updateBasic(int lid, LectureSaveRequest req, String status) {
        return jdbcTemplate.update(
                "UPDATE lectures SET name = ?, category = ?, level_ = ?, intro = ?, " +
                        "lprice = ?, status = ? WHERE lid = ?",
                req.getName(), req.getCategory(), req.getLevel(), req.getIntro(),
                req.getPrice() == null ? 0 : req.getPrice(), status, lid);
    }

    // ---------------- 파일 경로 갱신 (업로드 API 용) ----------------

    /** 영상(mp4) 경로 갱신. */
    public void updateVideo(int lid, String path) {
        jdbcTemplate.update("UPDATE lectures SET video = ? WHERE lid = ?", path, lid);
    }

    /** 자료(pdf/zip) 경로 갱신. */
    public void updateMaterial(int lid, String path) {
        jdbcTemplate.update("UPDATE lectures SET material = ? WHERE lid = ?", path, lid);
    }

    /** 썸네일(이미지) 경로 갱신. */
    public void updatePicture(int lid, String path) {
        jdbcTemplate.update("UPDATE lectures SET picture = ? WHERE lid = ?", path, lid);
    }
}