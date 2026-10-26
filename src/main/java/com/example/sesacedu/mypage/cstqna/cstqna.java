package com.example.sesacedu.mypage.cstqna;

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
 * GET /mypage/cstqna?status=&page=&size= (Notion API 명세서: 고객센터 1:1 문의 목록)
 *
 * 처리 순서: 1. Authorization의 JWT 검증 (없음·위조 401 UNAUTHORIZED, 만료 401 TOKEN_EXPIRED)
 * → 2. 쿼리 검증 (status ALL|PENDING|ANSWERED, page 0 이상, size 1~50, 아니면 400 INVALID_INPUT)
 * → 3. 건수 집계(항상 전체 기준) + 목록 조회 → 4. 200 CST_QNA_FOUND.
 * 조회 대상은 토큰 sub로만 정한다 (inquiries.user_id = 나). 정렬은 최신순 고정 (sort 파라미터 없음, Kim 2026-10-22).
 * 읽기는 제한 계정도 허용한다 (쓰기만 막음).
 */
@RestController
public class cstqna {

    /** 한 페이지 기본 개수 (Kim 2026-10-17: 마이페이지 목록은 5개씩) */
    static final int PAGE_SIZE = 5;

    private final JdbcTemplate jdbc;
    private final JwtUtil jwtUtil;

    public cstqna(JdbcTemplate jdbc, JwtUtil jwtUtil) {
        this.jdbc = jdbc;
        this.jwtUtil = jwtUtil;
    }

    @GetMapping("/mypage/cstqna")
    public ResponseEntity<Map<String, Object>> list(@RequestParam(required = false) String status,
                                                    @RequestParam(required = false) String page,
                                                    @RequestParam(required = false) String size,
                                                    HttpServletRequest request) {
        // 1. JWT 검증 (쿼리 검증보다 먼저)
        long userId = jwtUtil.getUid(request);

        // 2. 쿼리 검증. 빈 값은 기본값
        status = isBlank(status) ? "ALL" : status.trim();
        List<Map<String, String>> errors = new ArrayList<>();
        if (!status.equals("ALL") && !status.equals("PENDING") && !status.equals("ANSWERED")) {
            errors.add(error("status", "ALL, PENDING, ANSWERED 중 하나여야 합니다."));
        }
        Integer pageNo = parseInt(page, 0);
        if (pageNo == null || pageNo < 0) errors.add(error("page", "0 이상의 정수여야 합니다."));
        Integer pageSize = parseInt(size, PAGE_SIZE);
        if (pageSize == null || pageSize < 1 || pageSize > 50) errors.add(error("size", "1~50 사이의 정수여야 합니다."));
        if (!errors.isEmpty()) return invalidInput(errors);

        // 3-1. 탭 건수 (항상 전체 기준)
        Map<String, Object> c = jdbc.queryForMap(
                "SELECT COUNT(*) AS all_cnt, "
                        + "COALESCE(SUM(CASE WHEN answer IS NULL THEN 1 ELSE 0 END), 0) AS pending_cnt, "
                        + "COALESCE(SUM(CASE WHEN answer IS NOT NULL THEN 1 ELSE 0 END), 0) AS answered_cnt "
                        + "FROM inquiries WHERE user_id = ?", userId);
        long all = num(c.get("ALL_CNT"));
        long pending = num(c.get("PENDING_CNT"));
        long answered = num(c.get("ANSWERED_CNT"));
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("all", all);
        counts.put("pending", pending);
        counts.put("answered", answered);

        // 3-2. 목록. 필터는 검증된 값으로 고른 고정 문자열, 값은 모두 바인드
        String answerFilter = status.equals("ANSWERED") ? " AND answer IS NOT NULL"
                : status.equals("PENDING") ? " AND answer IS NULL" : "";
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT iqid, title, CASE WHEN answer IS NULL THEN 'PENDING' ELSE 'ANSWERED' END AS status,"
                        + "       created, answered_at"
                        + "  FROM inquiries"
                        + " WHERE user_id = ?" + answerFilter
                        + " ORDER BY created DESC, iqid DESC"
                        + " OFFSET ? ROWS FETCH NEXT ? ROWS ONLY",
                userId, (long) pageNo * pageSize, pageSize);

        List<Map<String, Object>> list = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            boolean isPending = "PENDING".equals(r.get("STATUS"));
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("inquiryId", num(r.get("IQID")));
            item.put("title", r.get("TITLE"));
            item.put("status", isPending ? "PENDING" : "ANSWERED");
            item.put("createdAt", iso(r.get("CREATED")));
            item.put("answeredAt", isPending ? null : iso(r.get("ANSWERED_AT")));
            list.add(item);
        }

        // 4. 응답. totalElements는 현재 status 필터 기준
        long total = status.equals("ANSWERED") ? answered : status.equals("PENDING") ? pending : all;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("counts", counts);
        result.put("inquiries", list);
        result.put("page", pageInfo(pageNo, pageSize, total));
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(body("CST_QNA_FOUND", "1:1 문의 목록을 조회했습니다.", result));
    }
}
