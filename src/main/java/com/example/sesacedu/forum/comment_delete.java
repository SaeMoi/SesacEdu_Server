package com.example.sesacedu.forum;

import com.example.sesacedu.forum.ForumSupport.ForumException;
import com.example.sesacedu.forum.ForumSupport.Member;
import com.example.sesacedu.global.JwtUtil;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.example.sesacedu.forum.ForumSupport.*;

/**
 * DELETE /forum/{pid}/comment?commentId= (Notion API 명세서: 커뮤니티 댓글 삭제, 2026-10-14 재작성본).
 *
 * 처리 순서 (한 트랜잭션): 1. JWT 검증 + DB 회원 확인 (401 UNAUTHORIZED / TOKEN_EXPIRED, restricted 403 ACCOUNT_RESTRICTED)
 * → 2. pid·commentId 누락 또는 양의 정수 아니면 400 INVALID_INPUT (틀린 항목 모두 errors[])
 * → 3. comments.cmid = commentId AND comments.pid = pid 를 FOR UPDATE, 없으면 404 COMMENT_NOT_FOUND
 *      (다른 글의 댓글 ID, 이미 삭제된 댓글, 글이 삭제되어 cascade된 댓글 모두 404)
 * → 4. comments.user_id = sub 이거나 DB users.auth = 3(관리자)이 아니면 403 NOT_COMMENT_AUTHOR
 *      (게시글 작성자라도 남의 댓글은 못 지움, Kim 확정 2026-10-14)
 * → 5. DELETE → 6. 지운 행 0이면 404 → 200 FORUM_COMMENT_DELETED { postId, commentId }.
 *
 * hard delete. 다시 삭제하면 404. audit_logs 기록 없음.
 * 의도된 취약점 후보 (아직 넣지 않음, 명세 결정 5): 4단계를 빼거나 3단계의 pid 조건을 빼면 순차 cmid IDOR.
 */
@RestController
public class comment_delete {

    private final JdbcTemplate jdbc;
    private final JwtUtil jwtUtil;

    public comment_delete(JdbcTemplate jdbc, JwtUtil jwtUtil) {
        this.jdbc = jdbc;
        this.jwtUtil = jwtUtil;
    }

    @Transactional
    @DeleteMapping("/forum/{pid}/comment")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable("pid") String pidParam,
                                                      @RequestParam(required = false) String commentId,
                                                      HttpServletRequest request) {
        // 1. JWT + 회원 상태
        Member me = requireActiveMember(jwtUtil, jdbc, request);

        // 2. 입력 검증
        List<Map<String, String>> errors = new ArrayList<>();
        Long pid = parsePositiveLong(pidParam);
        if (pid == null) errors.add(error("pid", "양의 정수여야 합니다."));
        Long cmid = parsePositiveLong(commentId);
        if (cmid == null) errors.add(error("commentId", "양의 정수여야 합니다."));
        if (!errors.isEmpty()) return invalidInput(errors);

        // 3. 대상 댓글 잠금 조회 (pid 범위 안에서만)
        List<Long> owner = jdbc.queryForList(
                "SELECT user_id FROM comments WHERE cmid = ? AND pid = ? FOR UPDATE", Long.class, cmid, pid);
        if (owner.isEmpty()) throw notFound();

        // 4. [의도된 취약점 — OWASP A01:2021 Broken Access Control / IDOR] 댓글 작성자·관리자 확인을 제거했다.
        //    로그인만 되어 있으면 commentId 를 넘겨 남의 댓글을 삭제할 수 있다 (객체 수준 인가 누락).

        // 5~6. 삭제
        int deleted = jdbc.update("DELETE FROM comments WHERE cmid = ? AND pid = ?", cmid, pid);
        if (deleted == 0) throw notFound();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("postId", pid);
        result.put("commentId", cmid);
        return ResponseEntity.ok(body("FORUM_COMMENT_DELETED", "댓글이 삭제되었습니다.", result));
    }

    private static ForumException notFound() {
        return new ForumException(404, "COMMENT_NOT_FOUND", "댓글을 찾을 수 없습니다.");
    }
}
