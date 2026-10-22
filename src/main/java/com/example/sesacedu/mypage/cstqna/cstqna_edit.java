package com.example.sesacedu.mypage.cstqna;

import com.example.sesacedu.global.JwtUtil;
import com.example.sesacedu.mypage.cstqna.CstQnaSupport.InquiryRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;

import static com.example.sesacedu.global.ApiResponse.*;

/**
 * PUT /mypage/cstqna/{iqid} (Notion API 명세서: 고객센터 1:1 문의 수정).
 *
 * 처리 순서 (한 트랜잭션): 1. JWT 검증 + DB 회원 확인 (401, restricted 403 ACCOUNT_RESTRICTED)
 * → 2. iqid 양의 정수 아니면 400 → 3. 입력 검증 (작성과 같음, 400 INVALID_INPUT)
 * → 4. SELECT ... FOR UPDATE (iqid AND user_id = 나), 없거나 남의 문의면 404 INQUIRY_NOT_FOUND
 * → 5. answer가 있으면 409 INQUIRY_ALREADY_ANSWERED
 * → 6. UPDATE title, content, updated ... AND answer IS NULL (0행이면 409) → 7. 200 CST_QNA_UPDATED, result = 상세.
 */
@RestController
public class cstqna_edit {

    private final JdbcTemplate jdbc;
    private final JwtUtil jwtUtil;

    public cstqna_edit(JdbcTemplate jdbc, JwtUtil jwtUtil) {
        this.jdbc = jdbc;
        this.jwtUtil = jwtUtil;
    }

    @Transactional
    @PutMapping("/mypage/cstqna/{iqid}")
    public ResponseEntity<Map<String, Object>> update(@PathVariable("iqid") String iqidParam,
                                                      @RequestBody(required = false) InquiryRequest req,
                                                      HttpServletRequest request) {
        // 1. JWT + 회원 상태
        long userId = CstQnaSupport.requireActiveMember(jwtUtil, jdbc, request);

        // 2. iqid
        Long iqid = CstQnaSupport.parseIqid(iqidParam);
        if (iqid == null) return CstQnaSupport.invalidIqid();

        // 3. 입력 검증
        List<Map<String, String>> errors = CstQnaSupport.validate(req);
        if (!errors.isEmpty()) return invalidInput(errors);

        // 4. 내 문의 잠금 조회
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT answer FROM inquiries WHERE iqid = ? AND user_id = ? FOR UPDATE", iqid, userId);
        if (rows.isEmpty()) return CstQnaSupport.notFound();

        // 5. 답변이 달렸으면 수정 불가
        if (rows.get(0).get("ANSWER") != null) return CstQnaSupport.alreadyAnswered("수정");

        // 6. 수정 (답변이 그사이 달렸으면 0행)
        int updated = jdbc.update(
                "UPDATE inquiries SET title = ?, content = ?, updated = SYSTIMESTAMP"
                        + " WHERE iqid = ? AND user_id = ? AND answer IS NULL",
                req.title.trim(), req.content.trim(), iqid, userId);
        if (updated == 0) return CstQnaSupport.alreadyAnswered("수정");

        // 7. 응답
        return ResponseEntity.ok()
                .body(body("CST_QNA_UPDATED", "1:1 문의가 수정되었습니다.", CstQnaSupport.findDetail(jdbc, iqid, userId)));
    }
}
