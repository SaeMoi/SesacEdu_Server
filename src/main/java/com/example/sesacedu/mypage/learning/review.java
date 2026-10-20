package com.example.sesacedu.mypage.learning;

import com.example.sesacedu.global.JwtUtil;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.example.sesacedu.global.ApiResponse.*;

/**
 * GET /mypage/{lectureid}/review?page=&size= (Notion API 명세서: 내 수강강좌 리뷰).
 *
 * 내 학습 카드의 「리뷰」 탭에서 그 강좌의 수강평 목록을 5개씩 보여준다.
 * 처리 순서: 1. Authorization의 JWT 검증 (없음·위조 401 UNAUTHORIZED, 만료 401 TOKEN_EXPIRED)
 * → 2. lectureid(양의 정수)·page(0 이상)·size(1~50, 기본 5) 검증, 틀리면 400 INVALID_INPUT (틀린 항목 모두 errors[])
 * → 3. 강좌 확인 (없으면 404 LECTURE_NOT_FOUND)
 * → 4. 내 리뷰(있으면) + 요약(개수·평균 별점) + 목록(최신 작성순) → 200 MY_REVIEWS_FOUND.
 * 수강평은 공개 정보라 수강 여부는 따지지 않는다. 작성자 개인정보는 닉네임만 내린다.
 */
@RestController
public class review {

    /** 한 페이지 기본 개수 (Kim 2026-10-17: 마이페이지 목록은 5개씩, 페이지 번호로 이동) */
    static final int PAGE_SIZE = 5;

    private final JdbcTemplate jdbc;
    private final JwtUtil jwtUtil;

    public review(JdbcTemplate jdbc, JwtUtil jwtUtil) {
        this.jdbc = jdbc;
        this.jwtUtil = jwtUtil;
    }

    @GetMapping("/mypage/{lectureid}/review")
    public ResponseEntity<Map<String, Object>> list(@PathVariable("lectureid") String lectureParam,
                                                    @RequestParam(required = false) String page,
                                                    @RequestParam(required = false) String size,
                                                    HttpServletRequest request) {
        // 1. JWT 검증 (입력 검증보다 먼저)
        long userId = jwtUtil.getUid(request);

        // 2. 입력 검증
        List<Map<String, String>> errors = new ArrayList<>();
        Integer lid = parseInt(lectureParam, -1);
        if (lid == null || lid < 1) errors.add(error("lectureId", "양의 정수여야 합니다."));
        Integer pageNo = parseInt(page, 0);
        if (pageNo == null || pageNo < 0) errors.add(error("page", "0 이상의 정수여야 합니다."));
        Integer pageSize = parseInt(size, PAGE_SIZE);
        if (pageSize == null || pageSize < 1 || pageSize > 50) errors.add(error("size", "1~50 사이의 정수여야 합니다."));
        if (!errors.isEmpty()) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("errors", errors);
            return ResponseEntity.badRequest().body(body("INVALID_INPUT", "입력값을 확인해주세요.", result));
        }

        // 3. 강좌 확인
        List<Map<String, Object>> lec = jdbc.queryForList("SELECT Lid, name FROM lectures WHERE Lid = ?", lid);
        if (lec.isEmpty()) {
            return ResponseEntity.status(404).body(body("LECTURE_NOT_FOUND", "강좌를 찾을 수 없습니다.", null));
        }
        Map<String, Object> lecture = new LinkedHashMap<>();
        lecture.put("lectureId", num(lec.get(0).get("LID")));
        lecture.put("title", lec.get(0).get("NAME"));

        // 4-1. 요약 (이 강좌 전체 기준)
        Map<String, Object> s = jdbc.queryForMap(
                "SELECT COUNT(*) AS cnt, AVG(star) AS avg_star FROM reviews WHERE Lid = ?", lid);
        long total = num(s.get("CNT"));
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("count", total);
        summary.put("averageStar", s.get("AVG_STAR") == null ? null
                : Math.round(((Number) s.get("AVG_STAR")).doubleValue() * 10) / 10.0);

        // 4-2. 내 리뷰 (없으면 null) — 목록 페이지와 무관하게 항상 위에 보여줄 수 있게 따로 준다
        List<Map<String, Object>> mine = jdbc.queryForList(
                "SELECT r.star, r.review, r.created, r.updated, u.nickname"
                        + "  FROM reviews r JOIN users u ON u.user_id = r.user_id"
                        + " WHERE r.Lid = ? AND r.user_id = ?", lid, userId);

        // 4-3. 목록 (최신 작성순). 바인드 변수만 사용
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT r.user_id AS writer, r.star, r.review, r.created, r.updated, u.nickname"
                        + "  FROM reviews r JOIN users u ON u.user_id = r.user_id"
                        + " WHERE r.Lid = ?"
                        + " ORDER BY r.created DESC, r.user_id DESC"
                        + " OFFSET ? ROWS FETCH NEXT ? ROWS ONLY",
                lid, (long) pageNo * pageSize, pageSize);
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            list.add(item(r, num(r.get("WRITER")) == userId));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("lecture", lecture);
        result.put("summary", summary);
        result.put("myReview", mine.isEmpty() ? null : item(mine.get(0), true));
        result.put("reviews", list);
        result.put("page", pageInfo(pageNo, pageSize, total));
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(body("MY_REVIEWS_FOUND", "강좌 리뷰를 조회했습니다.", result));
    }

    private static Map<String, Object> item(Map<String, Object> r, boolean isMine) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("authorNickname", r.get("NICKNAME"));
        item.put("star", num(r.get("STAR")));
        item.put("review", text(r.get("REVIEW")));
        item.put("createdAt", iso(r.get("CREATED")));
        item.put("updatedAt", iso(r.get("UPDATED")));
        item.put("isMine", isMine);
        return item;
    }

    /** reviews.review는 CLOB이라 드라이버가 Clob으로 줄 수 있다 */
    private static String text(Object v) {
        if (v == null) return "";
        if (v instanceof java.sql.Clob) {
            try {
                java.sql.Clob c = (java.sql.Clob) v;
                return c.getSubString(1, (int) c.length());
            } catch (java.sql.SQLException e) {
                throw new IllegalStateException(e);
            }
        }
        return String.valueOf(v);
    }
}
