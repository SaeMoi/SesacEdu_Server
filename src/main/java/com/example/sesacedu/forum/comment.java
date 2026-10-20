package com.example.sesacedu.forum;

import com.example.sesacedu.forum.ForumSupport.ForumException;
import com.example.sesacedu.forum.ForumSupport.Member;
import com.example.sesacedu.global.JwtUtil;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.example.sesacedu.forum.ForumSupport.*;

/**
 * POST /forum/{pid}/comment (Notion API 명세서: 커뮤니티 댓글 작성).
 *
 * 처리 순서: 1. JWT 검증 + DB 회원 확인 (없음·위조 401 UNAUTHORIZED, 만료 401 TOKEN_EXPIRED, 회원 없음 401, restricted 403 ACCOUNT_RESTRICTED)
 * → 2. pid(양의 정수)·content(일반 텍스트, 앞뒤 공백 제거 후 1~1,000자) 검증, 틀리면 400 INVALID_INPUT
 * → 3. posts 존재 확인, 없으면 404 POST_NOT_FOUND
 * → 4. INSERT comments(pid, user_id, content, created) → 5. 201 FORUM_COMMENT_CREATED (상세 comments[] 항목과 같은 형태).
 *
 * - 작성자는 토큰 sub, 작성 시각은 LOCALTIMESTAMP, updated는 null. 수강 권한은 요구하지 않는다.
 * - content는 HTML로 해석하지 않고 그대로 저장한다 (프론트가 일반 텍스트로 출력). 줄바꿈은 유지한다.
 * - 3과 4 사이에 글이 삭제되어 FK 위반이 나면 글이 정말 없는지 다시 보고 그때만 404. 다른 DB 오류는 500.
 * - 멱등 키 없음: 반복 요청은 댓글을 중복으로 만든다.
 */
@RestController
public class comment {

    private static final int CONTENT_MAX = 1_000;

    private final JdbcTemplate jdbc;
    private final JwtUtil jwtUtil;

    public comment(JdbcTemplate jdbc, JwtUtil jwtUtil) {
        this.jdbc = jdbc;
        this.jwtUtil = jwtUtil;
    }

    @PostMapping("/forum/{pid}/comment")
    public ResponseEntity<Map<String, Object>> create(@PathVariable("pid") String pidParam,
                                                      @RequestBody(required = false) CommentRequest req,
                                                      HttpServletRequest request) {
        // 1. JWT + 회원 상태
        Member me = requireActiveMember(jwtUtil, jdbc, request);
        if (req == null) req = new CommentRequest();

        // 2. 입력 검증
        List<Map<String, String>> errors = new ArrayList<>();
        Long pid = parsePositiveLong(pidParam);
        if (pid == null) errors.add(error("pid", "양의 정수여야 합니다."));
        String content = req.content == null ? "" : req.content.trim();
        if (content.isEmpty() || length(content) > CONTENT_MAX) {
            errors.add(error("content", "댓글은 1자 이상 1,000자 이하로 입력해주세요."));
        }
        if (!errors.isEmpty()) return invalidInput(errors);

        // 3. 대상 글
        if (!postExists(pid)) throw postNotFound();

        // 4. 저장
        KeyHolder kh = new GeneratedKeyHolder();
        try {
            jdbc.update(con -> {
                PreparedStatement ps = con.prepareStatement(
                        "INSERT INTO comments (pid, user_id, content, created) VALUES (?, ?, ?, LOCALTIMESTAMP)",
                        new String[]{"CMID"});
                ps.setLong(1, pid);
                ps.setLong(2, me.userId);
                ps.setString(3, content);
                return ps;
            }, kh);
        } catch (DataIntegrityViolationException e) {
            if (!postExists(pid)) throw postNotFound();   // 조회와 저장 사이 글 삭제 (comments.pid FK 위반)
            throw e;
        }
        long cmid = Objects.requireNonNull(kh.getKey()).longValue();

        // 5. 201. 저장된 값으로 응답
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT u.nickname, c.created FROM comments c LEFT JOIN users u ON u.user_id = c.user_id WHERE c.cmid = ?", cmid);
        Map<String, Object> result = forum_pid.comment(cmid, content, (String) row.get("NICKNAME"),
                row.get("CREATED"), null, true);
        return ResponseEntity.status(201).body(body("FORUM_COMMENT_CREATED", "댓글이 등록되었습니다.", result));
    }

    private boolean postExists(long pid) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM posts WHERE pid = ?", Long.class, pid);
        return n != null && n > 0;
    }

    private static ForumException postNotFound() {
        return new ForumException(404, "POST_NOT_FOUND", "게시글을 찾을 수 없습니다.");
    }

    /** 요청 본문. content 외 값(작성자 ID 등)은 받지 않는다 */
    public static class CommentRequest {
        public String content;
    }
}
