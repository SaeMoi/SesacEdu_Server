package com.example.sesacedu.forum;

import com.example.sesacedu.forum.ForumSupport.ForumException;
import com.example.sesacedu.global.JwtUtil;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
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
 * GET /forum/{pid}?page=&size= (Notion API 명세서: 커뮤니티 글 불러오기).
 *
 * 비로그인 조회 가능. 처리 순서: 1. pid(양의 정수)·page(0 이상)·size(1~50) 검증, 틀리면 400 INVALID_INPUT
 * → 2. posts.pid로 글 조회 + users 닉네임, 없으면 404 POST_NOT_FOUND
 * → 3. comments.pid = pid 건수 + 댓글 페이지 (created ASC, cmid ASC, 오래된 댓글부터) → 4. 200 FORUM_POST_FOUND.
 * page·size는 댓글에만 적용하고 본문은 항상 전체를 준다. 범위를 넘는 댓글 페이지는 comments: [].
 *
 * isMine (삭제 페이지 「결정 필요」 2·4번의 추천안 ①): Authorization에 유효한 로그인 토큰이 있으면
 * post.isMine = (posts.user_id = sub), comments[].isMine = (comments.user_id = sub). 토큰이 없거나 위조·만료면
 * 에러 없이 비로그인으로 보고 모두 false. 작성자 ID 자체는 내보내지 않는다. 관리자라도 isMine은 본인 글에만 true.
 * 개인정보(mail·phone·pw·token)는 반환하지 않는다.
 */
@RestController
public class forum_pid {

    private final JdbcTemplate jdbc;
    private final JwtUtil jwtUtil;

    public forum_pid(JdbcTemplate jdbc, JwtUtil jwtUtil) {
        this.jdbc = jdbc;
        this.jwtUtil = jwtUtil;
    }

    @GetMapping("/forum/{pid}")
    public ResponseEntity<Map<String, Object>> detail(@PathVariable("pid") String pidParam,
                                                      @RequestParam(required = false) String page,
                                                      @RequestParam(required = false) String size,
                                                      HttpServletRequest request) {
        // 1. 입력 검증
        List<Map<String, String>> errors = new ArrayList<>();
        Long pid = parsePositiveLong(pidParam);
        if (pid == null) errors.add(error("pid", "양의 정수여야 합니다."));
        Integer pageNo = parseInt(page, 0);
        if (pageNo == null || pageNo < 0) errors.add(error("page", "0 이상의 정수여야 합니다."));
        Integer pageSize = parseInt(size, 10);
        if (pageSize == null || pageSize < 1 || pageSize > 50) errors.add(error("size", "1~50 사이의 정수여야 합니다."));
        if (!errors.isEmpty()) return invalidInput(errors);

        Long viewer = optionalUid(jwtUtil, request);

        // 2. 게시글 (CLOB content는 RowMapper 안에서 읽는다)
        List<Map<String, Object>> found = jdbc.query(
                "SELECT p.pid, p.user_id, p.category, p.title, p.content, u.nickname, p.created, p.updated"
                        + "  FROM posts p LEFT JOIN users u ON u.user_id = p.user_id"
                        + " WHERE p.pid = ?",
                (rs, i) -> {
                    Map<String, Object> post = new LinkedHashMap<>();
                    post.put("postId", rs.getLong("pid"));
                    post.put("category", category(rs.getString("category")));
                    post.put("title", rs.getString("title"));
                    String content = rs.getString("content");
                    post.put("content", content == null ? "" : content);
                    post.put("contentFormat", "HTML");
                    post.put("authorNickname", rs.getString("nickname"));
                    post.put("createdAt", iso(rs.getTimestamp("created")));
                    post.put("updatedAt", iso(rs.getTimestamp("updated")));
                    post.put("isMine", viewer != null && viewer == rs.getLong("user_id"));
                    return post;
                }, pid);
        if (found.isEmpty()) throw new ForumException(404, "POST_NOT_FOUND", "게시글을 찾을 수 없습니다.");

        // 3. 댓글 건수 + 페이지
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM comments WHERE pid = ?", Long.class, pid);
        List<Map<String, Object>> comments = jdbc.query(
                "SELECT c.cmid, c.user_id, c.content, u.nickname, c.created, c.updated"
                        + "  FROM comments c LEFT JOIN users u ON u.user_id = c.user_id"
                        + " WHERE c.pid = ?"
                        + " ORDER BY c.created ASC, c.cmid ASC"
                        + " OFFSET ? ROWS FETCH NEXT ? ROWS ONLY",
                (rs, i) -> comment(rs.getLong("cmid"), rs.getString("content"), rs.getString("nickname"),
                        rs.getTimestamp("created"), rs.getTimestamp("updated"),
                        viewer != null && viewer == rs.getLong("user_id")),
                pid, (long) pageNo * pageSize, pageSize);

        // 4. 응답
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("post", found.get(0));
        result.put("comments", comments);
        result.put("page", pageInfo(pageNo, pageSize, total == null ? 0 : total));
        return ResponseEntity.ok(body("FORUM_POST_FOUND", "커뮤니티 게시글을 조회했습니다.", result));
    }

    /** comments[] 항목. 댓글 작성(POST /forum/{pid}/comment) 201 응답도 같은 형태 */
    static Map<String, Object> comment(long cmid, String content, String nickname, Object created, Object updated, boolean mine) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("commentId", cmid);
        c.put("content", content == null ? "" : content);
        c.put("authorNickname", nickname);
        c.put("createdAt", iso(created));
        c.put("updatedAt", iso(updated));
        c.put("isMine", mine);
        return c;
    }
}
