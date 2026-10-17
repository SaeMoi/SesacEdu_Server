package com.example.sesacedu.forum;

import com.example.sesacedu.forum.ForumSupport.ForumException;
import com.example.sesacedu.forum.ForumSupport.Member;
import com.example.sesacedu.global.JwtUtil;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.example.sesacedu.forum.ForumSupport.*;

/**
 * DELETE /forum/{pid} (Notion API 명세서: 커뮤니티 글 삭제, 2026-10-14 재작성본).
 *
 * 처리 순서 (한 트랜잭션): 1. JWT 검증 + DB 회원 확인 (401 UNAUTHORIZED / TOKEN_EXPIRED, restricted 403 ACCOUNT_RESTRICTED)
 * → 2. pid 양의 정수 아니면 400 INVALID_INPUT
 * → 3. SELECT ... FOR UPDATE, 없으면 404 POST_NOT_FOUND
 * → 4. posts.user_id = sub 이거나 DB users.auth = 3(관리자)이 아니면 403 NOT_POST_AUTHOR (토큰 role은 보지 않음)
 * → 5. 함께 지워질 댓글 수 → 6. DELETE posts (comments는 FK ON DELETE CASCADE로 DB가 지움)
 * → 7. 지운 행 0이면 404 (동시 삭제) → 200 FORUM_POST_DELETED { postId, deletedCommentCount }.
 *
 * hard delete. 다시 삭제하면 404. audit_logs는 ERD에서 주석 상태라 기록하지 않는다.
 * 의도된 취약점 후보 (아직 넣지 않음, 명세 결정 3): 4단계 작성자 확인을 빼면 순차 pid IDOR로 남의 글 삭제.
 */
@RestController
public class forum_delete {

    private final JdbcTemplate jdbc;
    private final JwtUtil jwtUtil;

    public forum_delete(JdbcTemplate jdbc, JwtUtil jwtUtil) {
        this.jdbc = jdbc;
        this.jwtUtil = jwtUtil;
    }

    @Transactional
    @DeleteMapping("/forum/{pid}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable("pid") String pidParam, HttpServletRequest request) {
        // 1. JWT + 회원 상태 (auth도 함께 읽어 4단계 관리자 판단에 쓴다)
        Member me = requireActiveMember(jwtUtil, jdbc, request);

        // 2. pid
        Long pid = parsePositiveLong(pidParam);
        if (pid == null) return invalidInput(Collections.singletonList(error("pid", "양의 정수여야 합니다.")));

        // 3. 대상 글 잠금 조회
        List<Long> owner = jdbc.queryForList("SELECT user_id FROM posts WHERE pid = ? FOR UPDATE", Long.class, pid);
        if (owner.isEmpty()) throw notFound();

        // 4. 작성자 또는 관리자
        if (owner.get(0) != me.userId && !me.isAdmin()) {
            throw new ForumException(403, "NOT_POST_AUTHOR", "본인이 작성한 게시글만 삭제할 수 있습니다.");
        }

        // 5~6. 댓글 수(응답용) → 삭제. 댓글은 cascade
        Long comments = jdbc.queryForObject("SELECT COUNT(*) FROM comments WHERE pid = ?", Long.class, pid);
        int deleted = jdbc.update("DELETE FROM posts WHERE pid = ?", pid);
        if (deleted == 0) throw notFound();

        // 7. 200
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("postId", pid);
        result.put("deletedCommentCount", comments == null ? 0 : comments);
        return ResponseEntity.ok(body("FORUM_POST_DELETED", "게시글이 삭제되었습니다.", result));
    }

    private static ForumException notFound() {
        return new ForumException(404, "POST_NOT_FOUND", "게시글을 찾을 수 없습니다.");
    }
}
