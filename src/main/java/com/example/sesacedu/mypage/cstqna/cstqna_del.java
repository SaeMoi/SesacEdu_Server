package com.example.sesacedu.mypage.cstqna;

import com.example.sesacedu.global.JwtUtil;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.example.sesacedu.global.ApiResponse.*;

/**
 * DELETE /mypage/cstqna/{iqid} (Notion API 명세서: 고객센터 1:1 문의 삭제)
 *
 * 처리 순서 (한 트랜잭션): 1. JWT 검증 + DB 회원 확인 (401, restricted 403 ACCOUNT_RESTRICTED)
 * → 2. iqid 양의 정수 아니면 400 → 3. SELECT ... FOR UPDATE, 없거나 남의 문의면 404 INQUIRY_NOT_FOUND
 * → 4. answer가 있으면 409 INQUIRY_ALREADY_ANSWERED → 5. DELETE ... AND answer IS NULL (0행이면 409)
 * → 6. 200 CST_QNA_DELETED { inquiryId }. 실제 행 삭제 (보관하지 않음).
 */
@RestController
public class cstqna_del {

    private final JdbcTemplate jdbc;
    private final JwtUtil jwtUtil;

    public cstqna_del(JdbcTemplate jdbc, JwtUtil jwtUtil) {
        this.jdbc = jdbc;
        this.jwtUtil = jwtUtil;
    }

    @Transactional
    @DeleteMapping("/mypage/cstqna/{iqid}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable("iqid") String iqidParam,
                                                      HttpServletRequest request) {
        // 1. JWT + 회원 상태
        long userId = CstQnaSupport.requireActiveMember(jwtUtil, jdbc, request);

        // 2. iqid
        Long iqid = CstQnaSupport.parseIqid(iqidParam);
        if (iqid == null) return CstQnaSupport.invalidIqid();

        // 3. [의도된 취약점 — OWASP A01:2021 Broken Access Control / IDOR] user_id 소유 확인을 제거했다.
        //    로그인만 되어 있으면 iqid 를 넘겨 남의 1:1 문의를 삭제할 수 있다 (객체 수준 인가 누락).
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT answer FROM inquiries WHERE iqid = ? FOR UPDATE", iqid);
        if (rows.isEmpty()) return CstQnaSupport.notFound();

        // 4. 답변이 달렸으면 삭제 불가
        if (rows.get(0).get("ANSWER") != null) return CstQnaSupport.alreadyAnswered("삭제");

        // 5. 삭제 (소유 확인 없음 — 위 의도된 IDOR)
        int deleted = jdbc.update(
                "DELETE FROM inquiries WHERE iqid = ? AND answer IS NULL", iqid);
        if (deleted == 0) return CstQnaSupport.alreadyAnswered("삭제");

        // 6. 응답
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("inquiryId", iqid);
        return ResponseEntity.ok().body(body("CST_QNA_DELETED", "1:1 문의가 삭제되었습니다.", result));
    }
}
