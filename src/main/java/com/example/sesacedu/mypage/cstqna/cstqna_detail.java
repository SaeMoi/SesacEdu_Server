package com.example.sesacedu.mypage.cstqna;

import com.example.sesacedu.global.JwtUtil;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.util.Map;

import static com.example.sesacedu.global.ApiResponse.*;

/**
 * GET /mypage/cstqna/{iqid} (Notion API 명세서: 고객센터 1:1 문의 상세)
 *
 * 처리 순서: 1. JWT 검증 (401 UNAUTHORIZED / TOKEN_EXPIRED)
 * → 2. iqid가 양의 정수가 아니면 400 INVALID_INPUT
 * → 3. WHERE iqid = ? AND user_id = 나 (+ 답변 관리자 닉네임 LEFT JOIN). 없거나 남의 문의면 404 INQUIRY_NOT_FOUND
 * → 4. 200 CST_QNA_DETAIL_FOUND { inquiryId, title, content, status, createdAt, updatedAt, answer, answeredBy{nickname}, answeredAt, editable }.
 */
@RestController
public class cstqna_detail {

    private final JdbcTemplate jdbc;
    private final JwtUtil jwtUtil;

    public cstqna_detail(JdbcTemplate jdbc, JwtUtil jwtUtil) {
        this.jdbc = jdbc;
        this.jwtUtil = jwtUtil;
    }

    @GetMapping("/mypage/cstqna/{iqid}")
    public ResponseEntity<Map<String, Object>> detail(@PathVariable("iqid") String iqidParam,
                                                      HttpServletRequest request) {
        // 1. JWT 검증
        long userId = jwtUtil.getUid(request);

        // 2. iqid
        Long iqid = CstQnaSupport.parseIqid(iqidParam);
        if (iqid == null) return CstQnaSupport.invalidIqid();

        // 3. 내 문의만 조회
        Map<String, Object> result = CstQnaSupport.findDetail(jdbc, iqid, userId);
        if (result == null) return CstQnaSupport.notFound();

        // 4. 응답
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(body("CST_QNA_DETAIL_FOUND", "1:1 문의를 조회했습니다.", result));
    }
}
