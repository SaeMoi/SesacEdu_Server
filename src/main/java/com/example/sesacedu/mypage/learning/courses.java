package com.example.sesacedu.mypage.learning;

import com.example.sesacedu.global.JwtUtil;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.example.sesacedu.global.ApiResponse.*;

/**
 * GET /mypage/courses?status=&sort=&page=&size= (Notion API 명세서: 내 학습 (수강 강좌 목록)).
 *
 * 처리 순서: 1. Authorization의 JWT 검증 (없음·위조 401 UNAUTHORIZED, 만료 401 TOKEN_EXPIRED)
 * → 2. 쿼리 검증 (status ALL|IN_PROGRESS|COMPLETED, sort RECENT_ENROLL|TITLE, page 0 이상, size 1~50, 아니면 400 INVALID_INPUT)
 * → 3. 건수 집계(항상 전체 기준) + 목록 조회 → 4. 200 MY_COURSES_FOUND.
 * 조회 대상은 토큰 sub로만 정한다 (enrollments.user_id = 나, IDOR 없음). 개인정보라 Cache-Control: no-store.
 *
 * enrollments.done 기준 최소 버전: 진도율·최근 학습순 없음. enrolledAt은 enrollments.enrolled_at
 * (없으면 null, 정렬 시 뒤로). 표기는 ERD(uid/Lid)지만 실제 회원 컬럼명은 user_id (schema.sql).
 * category.code는 한글 lectures.category를 앱 상수로 매핑한다 (ERD에 code 컬럼 없음, 모르는 값은 ETC).
 */
@RestController
public class courses {

    /** lectures.category(한글) → code. 명세 「제안(메모)」의 앱 enum 매핑 */
    private static final Map<String, String> CATEGORY_CODES = new LinkedHashMap<>();
    static {
        CATEGORY_CODES.put("웹 보안", "WEB_SECURITY");
        CATEGORY_CODES.put("네트워크", "NETWORK");
        CATEGORY_CODES.put("개발 기초", "DEV_BASICS");
        CATEGORY_CODES.put("로그 분석", "LOG_ANALYSIS");
        CATEGORY_CODES.put("클라우드", "CLOUD");
    }

    private final JdbcTemplate jdbc;
    private final JwtUtil jwtUtil;

    public courses(JdbcTemplate jdbc, JwtUtil jwtUtil) {
        this.jdbc = jdbc;
        this.jwtUtil = jwtUtil;
    }

    // /mypage/lectures: API 명세 경로 (프런트 통합 브랜치가 사용). /mypage/courses: 기존 경로
    @GetMapping({"/mypage/courses", "/mypage/lectures"})
    public ResponseEntity<Map<String, Object>> list(@RequestParam(required = false) String status,
                                                    @RequestParam(required = false) String sort,
                                                    @RequestParam(required = false) String page,
                                                    @RequestParam(required = false) String size,
                                                    HttpServletRequest request) {
        // 1. JWT 검증 (쿼리 검증보다 먼저)
        long userId = jwtUtil.getUid(request);

        // 2. 쿼리 검증. 빈 값은 기본값 (엔드포인트가 ?status=&sort= 형태)
        status = isBlank(status) ? "ALL" : status.trim();
        sort = isBlank(sort) ? "RECENT_ENROLL" : sort.trim();
        List<Map<String, String>> errors = new ArrayList<>();
        if (!status.equals("ALL") && !status.equals("IN_PROGRESS") && !status.equals("COMPLETED")) {
            errors.add(error("status", "ALL, IN_PROGRESS, COMPLETED 중 하나여야 합니다."));
        }
        if (!sort.equals("RECENT_ENROLL") && !sort.equals("TITLE")) {
            errors.add(error("sort", "RECENT_ENROLL, TITLE 중 하나여야 합니다."));
        }
        Integer pageNo = parseInt(page, 0);
        if (pageNo == null || pageNo < 0) errors.add(error("page", "0 이상의 정수여야 합니다."));
        Integer pageSize = parseInt(size, 10);
        if (pageSize == null || pageSize < 1 || pageSize > 50) errors.add(error("size", "1~50 사이의 정수여야 합니다."));
        if (!errors.isEmpty()) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("errors", errors);
            return ResponseEntity.badRequest().body(body("INVALID_INPUT", "입력값을 확인해주세요.", result));
        }

        // 3-1. 필터 탭 건수 (status와 무관하게 항상 전체 기준, 한 번의 집계)
        Map<String, Object> c = jdbc.queryForMap(
                "SELECT COUNT(*) AS all_cnt, "
                        + "COALESCE(SUM(CASE WHEN done = 0 THEN 1 ELSE 0 END), 0) AS in_progress_cnt, "
                        + "COALESCE(SUM(CASE WHEN done = 1 THEN 1 ELSE 0 END), 0) AS completed_cnt "
                        + "FROM enrollments WHERE user_id = ?", userId);
        long all = num(c.get("ALL_CNT"));
        long inProgress = num(c.get("IN_PROGRESS_CNT"));
        long completed = num(c.get("COMPLETED_CNT"));
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("all", all);
        counts.put("inProgress", inProgress);
        counts.put("completed", completed);

        // 3-2. 목록. 바인드 변수만 사용, ORDER BY는 검증된 sort로 고른 고정 문자열
        String doneFilter = status.equals("COMPLETED") ? " AND e.done = 1"
                : status.equals("IN_PROGRESS") ? " AND e.done = 0" : "";
        String orderBy = sort.equals("TITLE")
                ? " ORDER BY t.title ASC, t.course_id DESC"
                : " ORDER BY t.enrolled_at DESC NULLS LAST, t.course_id DESC";
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM ("
                        + " SELECT e.Lid AS course_id, l.name AS title, l.category AS category, l.picture AS thumbnail_url,"
                        + "        e.done AS is_done,"
                        + "        CASE WHEN l.material IS NOT NULL THEN 1 ELSE 0 END AS has_materials,"
                        + "        CASE WHEN EXISTS (SELECT 1 FROM reviews r WHERE r.Lid = e.Lid AND r.user_id = e.user_id)"
                        + "             THEN 1 ELSE 0 END AS review_written,"
                        + "        e.enrolled_at AS enrolled_at"
                        + "   FROM enrollments e JOIN lectures l ON l.Lid = e.Lid"
                        + "  WHERE e.user_id = ?" + doneFilter
                        + ") t" + orderBy
                        + " OFFSET ? ROWS FETCH NEXT ? ROWS ONLY",
                userId, (long) pageNo * pageSize, pageSize);

        List<Map<String, Object>> list = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            String category = (String) r.get("CATEGORY");
            Map<String, Object> cat = new LinkedHashMap<>();
            cat.put("code", CATEGORY_CODES.getOrDefault(category, "ETC"));
            cat.put("name", category);

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("courseId", num(r.get("COURSE_ID")));
            item.put("category", cat);
            item.put("title", r.get("TITLE"));
            item.put("thumbnailUrl", r.get("THUMBNAIL_URL"));
            item.put("status", num(r.get("IS_DONE")) == 1 ? "COMPLETED" : "IN_PROGRESS");
            item.put("enrolledAt", iso(r.get("ENROLLED_AT")));
            item.put("reviewWritten", num(r.get("REVIEW_WRITTEN")) == 1);
            item.put("hasMaterials", num(r.get("HAS_MATERIALS")) == 1);
            list.add(item);
        }

        // 4. 응답. totalElements는 현재 status 필터 기준
        long total = status.equals("COMPLETED") ? completed : status.equals("IN_PROGRESS") ? inProgress : all;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("counts", counts);
        result.put("courses", list);
        result.put("page", pageInfo(pageNo, pageSize, total));
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(body("MY_COURSES_FOUND", "내 학습 목록을 조회했습니다.", result));
    }
}
