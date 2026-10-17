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
 * GET /mypage/qna?status=&sort=&page=&size= (Notion API 명세서: 내 Q&A).
 *
 * 처리 순서: 1. Authorization의 JWT 검증 (없음·위조 401 UNAUTHORIZED, 만료 401 TOKEN_EXPIRED)
 * → 2. 쿼리 검증 (status ALL|PENDING|ANSWERED, sort RECENT|TITLE|STATUS, page 0 이상, size 1~50, 아니면 400 INVALID_INPUT)
 * → 3. 건수 집계(항상 전체 기준) + 목록 조회 → 4. 200 MY_QNA_FOUND.
 * 조회 대상은 토큰 sub로만 정한다 (qna.user_id = 나, 강의 구분 없이). 내 글 목록이라 비밀글 제목도 가리지 않는다.
 * status는 qna.answer IS NULL이면 PENDING. answeredAt은 qna.answered_at이며 PENDING이면 항상 null.
 * lecturer는 담당 강사 (lectures.user_id → instructors.iname), 강사 행이 없으면 null.
 *
 * 의도된 취약점 없음. 남의 비밀글 IDOR은 Q&A 상세 API에 둔다 (명세 「의도된 취약점」).
 */
@RestController
public class qna {

    /** 한 페이지 기본 개수 (Kim 2026-10-17: 마이페이지 목록은 5개씩, 페이지 번호로 이동) */
    static final int PAGE_SIZE = 5;

    private final JdbcTemplate jdbc;
    private final JwtUtil jwtUtil;

    public qna(JdbcTemplate jdbc, JwtUtil jwtUtil) {
        this.jdbc = jdbc;
        this.jwtUtil = jwtUtil;
    }

    @GetMapping("/mypage/qna")
    public ResponseEntity<Map<String, Object>> list(@RequestParam(required = false) String status,
                                                    @RequestParam(required = false) String sort,
                                                    @RequestParam(required = false) String page,
                                                    @RequestParam(required = false) String size,
                                                    HttpServletRequest request) {
        // 1. JWT 검증 (쿼리 검증보다 먼저)
        long userId = jwtUtil.getUid(request);

        // 2. 쿼리 검증. 빈 값은 기본값
        status = isBlank(status) ? "ALL" : status.trim();
        sort = isBlank(sort) ? "RECENT" : sort.trim();
        List<Map<String, String>> errors = new ArrayList<>();
        if (!status.equals("ALL") && !status.equals("PENDING") && !status.equals("ANSWERED")) {
            errors.add(error("status", "ALL, PENDING, ANSWERED 중 하나여야 합니다."));
        }
        if (!sort.equals("RECENT") && !sort.equals("TITLE") && !sort.equals("STATUS")) {
            errors.add(error("sort", "RECENT, TITLE, STATUS 중 하나여야 합니다."));
        }
        Integer pageNo = parseInt(page, 0);
        if (pageNo == null || pageNo < 0) errors.add(error("page", "0 이상의 정수여야 합니다."));
        Integer pageSize = parseInt(size, PAGE_SIZE);
        if (pageSize == null || pageSize < 1 || pageSize > 50) errors.add(error("size", "1~50 사이의 정수여야 합니다."));
        if (!errors.isEmpty()) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("errors", errors);
            return ResponseEntity.badRequest().body(body("INVALID_INPUT", "입력값을 확인해주세요.", result));
        }

        // 3-1. 필터 탭 건수 (항상 전체 기준)
        Map<String, Object> c = jdbc.queryForMap(
                "SELECT COUNT(*) AS all_cnt, "
                        + "COALESCE(SUM(CASE WHEN answer IS NULL THEN 1 ELSE 0 END), 0) AS pending_cnt, "
                        + "COALESCE(SUM(CASE WHEN answer IS NOT NULL THEN 1 ELSE 0 END), 0) AS answered_cnt "
                        + "FROM qna WHERE user_id = ?", userId);
        long all = num(c.get("ALL_CNT"));
        long pending = num(c.get("PENDING_CNT"));
        long answered = num(c.get("ANSWERED_CNT"));
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("all", all);
        counts.put("pending", pending);
        counts.put("answered", answered);

        // 3-2. 목록. 바인드 변수만 사용, 필터·ORDER BY는 검증된 값으로 고른 고정 문자열
        String answerFilter = status.equals("ANSWERED") ? " AND q.answer IS NOT NULL"
                : status.equals("PENDING") ? " AND q.answer IS NULL" : "";
        String orderBy = sort.equals("TITLE") ? " ORDER BY q.title ASC, q.qid DESC"
                : sort.equals("STATUS") ? " ORDER BY CASE WHEN q.answer IS NULL THEN 0 ELSE 1 END ASC, q.created DESC, q.qid DESC"
                : " ORDER BY q.created DESC, q.qid DESC";
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT q.qid AS qna_id, q.Lid AS lecture_id, l.name AS lecture_title, q.title AS title,"
                        + "       i.iname AS lecturer,"
                        + "       CASE WHEN q.answer IS NULL THEN 'PENDING' ELSE 'ANSWERED' END AS status,"
                        + "       q.isSecret AS is_secret, q.created AS created_at, q.answered_at AS answered_at"
                        + "  FROM qna q"
                        + "  JOIN lectures l ON l.Lid = q.Lid"
                        + "  LEFT JOIN instructors i ON i.user_id = l.user_id"
                        + " WHERE q.user_id = ?" + answerFilter
                        + orderBy
                        + " OFFSET ? ROWS FETCH NEXT ? ROWS ONLY",
                userId, (long) pageNo * pageSize, pageSize);

        List<Map<String, Object>> list = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            boolean isPending = "PENDING".equals(r.get("STATUS"));
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("qnaId", num(r.get("QNA_ID")));
            item.put("lectureId", num(r.get("LECTURE_ID")));
            item.put("lectureTitle", r.get("LECTURE_TITLE"));
            item.put("title", r.get("TITLE"));
            item.put("lecturer", r.get("LECTURER"));
            item.put("status", isPending ? "PENDING" : "ANSWERED");
            item.put("isSecret", num(r.get("IS_SECRET")) == 1);
            item.put("createdAt", iso(r.get("CREATED_AT")));
            item.put("answeredAt", isPending ? null : iso(r.get("ANSWERED_AT")));
            list.add(item);
        }

        // 4. 응답. totalElements는 현재 status 필터 기준
        long total = status.equals("ANSWERED") ? answered : status.equals("PENDING") ? pending : all;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("counts", counts);
        result.put("questions", list);
        result.put("page", pageInfo(pageNo, pageSize, total));
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(body("MY_QNA_FOUND", "내 Q&A 목록을 조회했습니다.", result));
    }
}
