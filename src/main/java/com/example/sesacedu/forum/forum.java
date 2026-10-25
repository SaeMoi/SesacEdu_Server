package com.example.sesacedu.forum;

import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.example.sesacedu.forum.ForumSupport.*;

/**
 * GET /forum?sort=&page=&size= (Notion API 명세서: 커뮤니티 메인 화면).
 *
 * 비로그인 조회 가능. sort는 정렬이 아니라 탭(카테고리) 선택값: ALL / QUESTION / STUDY / PROJECT / REVIEW, 빈 값·생략은 ALL.
 * 처리 순서: 1. 쿼리 검증 (sort 허용값, page 0 이상(0 = 1페이지), size 1~50 기본 15, 아니면 400 INVALID_INPUT, 틀린 항목 모두 errors[])
 * → 2. 건수 집계 → 3. 목록 조회 (모든 탭 작성 시각 최신순) → 4. 200 FORUM_POSTS_FOUND.
 *
 * - QUESTION / STUDY / PROJECT : posts.category = 그 값 (posts + users)
 * - REVIEW : reviews + users + lectures. posts에 복제하지 않는다. postId 없음 → reviewRef { lectureId, userId }
 * - ALL    : 위 세 카테고리의 posts + reviews를 UNION ALL로 합친 뒤 한 번만 정렬·페이징한다.
 * 정렬 = created DESC, type ASC, source_id DESC, source_uid DESC (POST: pid, 0 / REVIEW: Lid, user_id).
 * sort 값은 SQL에 직접 넣지 않고, 검증된 값으로 고른 고정 SQL + 바인드 변수만 쓴다.
 * excerpt는 본문(posts.content HTML / reviews.review 텍스트)에서 서버가 만든다 (최대 150자).
 * 응답에 mail·phone·pw·token 등 개인정보는 넣지 않는다.
 */
@RestController
public class forum {

    /** 목록 한 페이지 게시글 수 기본값 (Kim 2026-10-16: 15개씩, 페이지 번호로 이동) */
    static final int PAGE_SIZE = 15;

    private static final List<String> SORTS = Arrays.asList("ALL", "QUESTION", "STUDY", "PROJECT", "REVIEW");

    private static final String POST_SELECT =
            "SELECT CAST('POST' AS VARCHAR2(10)) AS type, p.pid AS source_id, CAST(0 AS NUMBER(19)) AS source_uid,"
                    + "       p.category AS category, p.title AS title, p.content AS body, u.nickname AS nickname,"
                    + "       p.created AS created, CAST(NULL AS NUMBER(19)) AS lecture_id,"
                    + "       CAST(NULL AS VARCHAR2(200)) AS lecture_title, CAST(NULL AS NUMBER(1)) AS star"
                    + "  FROM posts p LEFT JOIN users u ON u.user_id = p.user_id";
    private static final String REVIEW_SELECT =
            "SELECT CAST('REVIEW' AS VARCHAR2(10)) AS type, r.Lid AS source_id, r.user_id AS source_uid,"
                    + "       CAST('REVIEW' AS VARCHAR2(50)) AS category, l.name || ' 수강평' AS title, r.review AS body,"
                    + "       u.nickname AS nickname, r.created AS created, r.Lid AS lecture_id,"
                    + "       l.name AS lecture_title, r.star AS star"
                    + "  FROM reviews r JOIN lectures l ON l.Lid = r.Lid LEFT JOIN users u ON u.user_id = r.user_id";
    private static final String POST_CATEGORIES = " WHERE p.category IN ('QUESTION', 'STUDY', 'PROJECT')";
    private static final String ORDER_PAGE =
            " ORDER BY created DESC, type ASC, source_id DESC, source_uid DESC OFFSET ? ROWS FETCH NEXT ? ROWS ONLY";

    private final JdbcTemplate jdbc;

    public forum(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/forum")
    public ResponseEntity<Map<String, Object>> list(@RequestParam(required = false) String sort,
                                                    @RequestParam(required = false) String page,
                                                    @RequestParam(required = false) String size) {
        // 1. 쿼리 검증. 빈 값은 기본값
        sort = isBlank(sort) ? "ALL" : sort.trim();
        List<Map<String, String>> errors = new ArrayList<>();
        // [의도된 취약점 — OWASP A03:2021 Injection (SQL Injection)] sort 허용값 화이트리스트 검증을 제거했다.
        //   화이트리스트가 유일한 방어였고, 아래 default 분기에서 sort 를 바인드 변수가 아니라 문자열로 이어 붙이므로
        //   비로그인 상태에서 ?sort= 에 UNION 페이로드를 넣어 임의 테이블·스키마 정보를 목록 응답(title·body·nickname)으로 추출할 수 있다.
        Integer pageNo = parseInt(page, 0);
        if (pageNo == null || pageNo < 0) errors.add(error("page", "0 이상의 정수여야 합니다."));
        Integer pageSize = parseInt(size, PAGE_SIZE);
        if (pageSize == null || pageSize < 1 || pageSize > 50) errors.add(error("size", "1~50 사이의 정수여야 합니다."));
        if (!errors.isEmpty()) return invalidInput(errors);

        // 2~3. 탭별 고정 SQL (조인 조건을 건수와 목록에 똑같이 써서 totalElements와 목록이 어긋나지 않게)
        long offset = (long) pageNo * pageSize;
        long total;
        String listSql;
        Object[] args;
        switch (sort) {
            case "ALL":
                total = count("SELECT COUNT(*) FROM posts p" + POST_CATEGORIES)
                        + count("SELECT COUNT(*) FROM reviews r JOIN lectures l ON l.Lid = r.Lid");
                listSql = "SELECT * FROM (" + POST_SELECT + POST_CATEGORIES + " UNION ALL " + REVIEW_SELECT + ") t" + ORDER_PAGE;
                args = new Object[]{offset, pageSize};
                break;
            case "REVIEW":
                total = count("SELECT COUNT(*) FROM reviews r JOIN lectures l ON l.Lid = r.Lid");
                listSql = "SELECT * FROM (" + REVIEW_SELECT + ") t" + ORDER_PAGE;
                args = new Object[]{offset, pageSize};
                break;
            default: // QUESTION / STUDY / PROJECT (+ 화이트리스트 제거로 임의 값)
                total = count("SELECT COUNT(*) FROM posts p WHERE p.category = ?", sort);
                // [의도된 취약점 — SQL Injection] sort 를 바인드 변수 대신 문자열로 직접 이어 붙인다.
                listSql = "SELECT * FROM (" + POST_SELECT + " WHERE p.category = '" + sort + "') t" + ORDER_PAGE;
                args = new Object[]{offset, pageSize};
        }

        // CLOB(content·review)은 RowMapper 안에서 getString으로 읽는다 (연결이 닫힌 뒤 CLOB을 읽지 않도록)
        List<Map<String, Object>> posts = jdbc.query(listSql, (rs, i) -> {
            boolean review = "REVIEW".equals(rs.getString("type").trim());
            String bodyText = rs.getString("body");
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("type", review ? "REVIEW" : "POST");
            item.put("postId", review ? null : rs.getLong("source_id"));
            if (review) {
                Map<String, Object> ref = new LinkedHashMap<>();
                ref.put("lectureId", rs.getLong("source_id"));
                ref.put("userId", rs.getLong("source_uid"));
                item.put("reviewRef", ref);
            } else {
                item.put("reviewRef", null);
            }
            item.put("category", category(review ? "REVIEW" : rs.getString("category")));
            item.put("title", rs.getString("title"));
            item.put("excerpt", excerpt(review ? collapse(bodyText) : htmlText(bodyText)));
            item.put("authorNickname", rs.getString("nickname"));
            item.put("createdAt", iso(rs.getTimestamp("created")));
            item.put("lectureId", review ? rs.getLong("lecture_id") : null);
            item.put("lectureTitle", review ? rs.getString("lecture_title") : null);
            item.put("star", review ? rs.getInt("star") : null);
            return item;
        }, args);

        // 4. 응답. 범위를 넘는 페이지는 posts: []
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("selectedCategory", sort);
        result.put("posts", posts);
        result.put("page", pageInfo(pageNo, pageSize, total));
        return ResponseEntity.ok(body("FORUM_POSTS_FOUND", "커뮤니티 게시글을 조회했습니다.", result));
    }

    private long count(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }
}
