package com.example.sesacedu.forum;

import com.example.sesacedu.forum.ForumSupport.Member;
import com.example.sesacedu.global.JwtUtil;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.example.sesacedu.forum.ForumSupport.*;

/**
 * POST /forum/posts (Notion API 명세서: 커뮤니티 글 작성).
 *
 * 처리 순서: 1. JWT 검증 + DB 회원 확인 (없음·위조 401 UNAUTHORIZED, 만료 401 TOKEN_EXPIRED, 회원 없음 401, restricted 403 ACCOUNT_RESTRICTED)
 * → 2. 입력 검증 (category QUESTION|STUDY|PROJECT, title 앞뒤 공백 제거 후 1~200자, content 원본 50,000자 이하,
 *      허용 서식만 남긴 뒤 보이는 텍스트 1~10,000자. 틀린 항목 모두 400 INVALID_INPUT errors[])
 * → 3. INSERT posts(user_id, category, title, content, created) → 4. 201 FORUM_POST_CREATED + Location: /forum/{pid}.
 *
 * - 작성자는 토큰 sub로만 정한다. 요청의 작성자 ID·작성 시각·excerpt는 받지 않는다 (DTO에 필드 없음 → 무시).
 * - category는 QUESTION / STUDY / PROJECT 그대로 저장 (Kim 확정 2026-10-14, 2026-10-16 프론트 탭에 맞춰 FREE→PROJECT). ALL·REVIEW는 거절.
 * - content는 HTML. jsoup Safelist로 허용 서식만 남긴 값을 저장한다 (script·on* 속성·javascript: 링크 제거).
 * - created는 LOCALTIMESTAMP (RDS의 SYSTIMESTAMP는 UTC라 9시간 어긋남), updated는 null.
 * - 멱등 키 없음: 같은 요청을 반복하면 글이 중복 등록된다 (명세 「프론트 처리」).
 */
@RestController
public class forum_posts {

    private static final List<String> CATEGORIES = Arrays.asList("QUESTION", "STUDY", "PROJECT");
    private static final int TITLE_MAX = 200;
    private static final int CONTENT_RAW_MAX = 50_000;
    private static final int CONTENT_TEXT_MAX = 10_000;

    private final JdbcTemplate jdbc;
    private final JwtUtil jwtUtil;

    public forum_posts(JdbcTemplate jdbc, JwtUtil jwtUtil) {
        this.jdbc = jdbc;
        this.jwtUtil = jwtUtil;
    }

    @Transactional
    @PostMapping("/forum/posts")
    public ResponseEntity<Map<String, Object>> create(@RequestBody(required = false) PostRequest req,
                                                      HttpServletRequest request) {
        // 1. JWT + 회원 상태 (입력 검증보다 먼저)
        Member me = requireActiveMember(jwtUtil, jdbc, request);
        if (req == null) req = new PostRequest();

        // 2. 입력 검증
        List<Map<String, String>> errors = new ArrayList<>();
        String category = req.category == null ? "" : req.category.trim();
        if (category.isEmpty()) {
            errors.add(error("category", "카테고리를 선택해주세요."));
        } else if (!CATEGORIES.contains(category)) {
            errors.add(error("category", "QUESTION, STUDY, PROJECT 중 하나여야 합니다."));
        }

        String title = req.title == null ? "" : req.title.trim();
        if (title.isEmpty()) {
            errors.add(error("title", "제목을 입력해주세요."));
        } else if (length(title) > TITLE_MAX) {
            errors.add(error("title", "제목은 200자 이하로 입력해주세요."));
        }

        String content = null;
        if (req.content == null || req.content.trim().isEmpty()) {
            errors.add(error("content", "내용을 입력해주세요."));
        } else if (req.content.length() > CONTENT_RAW_MAX) {
            errors.add(error("content", "본문은 50,000자 이하로 보내주세요."));
        } else {
            content = sanitizeHtml(req.content);
            int visible = length(htmlText(content));
            if (visible == 0) {
                errors.add(error("content", "내용을 입력해주세요."));      // 태그만 있거나 공백뿐인 본문
            } else if (visible > CONTENT_TEXT_MAX) {
                errors.add(error("content", "본문은 10,000자 이하로 입력해주세요."));
            }
        }
        if (!errors.isEmpty()) return invalidInput(errors);

        // 3. 저장. pid는 IDENTITY로 생성
        KeyHolder kh = new GeneratedKeyHolder();
        // [의도된 취약점 — OWASP A03:2021 Injection (저장형 XSS)] 정제(sanitizeHtml)한 값 대신 원본 HTML을 저장한다.
        //   저장된 스크립트가 이 글을 여는 모든 사용자(관리자 포함)의 브라우저에서 실행된다 → 세션/토큰 탈취로 이어질 수 있다.
        String html = req.content;
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO posts (user_id, category, title, content, created) VALUES (?, ?, ?, ?, LOCALTIMESTAMP)",
                    new String[]{"PID"});
            ps.setLong(1, me.userId);
            ps.setString(2, category);
            ps.setString(3, title);
            ps.setString(4, html);
            return ps;
        }, kh);
        long pid = Objects.requireNonNull(kh.getKey()).longValue();
        Object created = jdbc.queryForObject("SELECT created FROM posts WHERE pid = ?", Object.class, pid);

        // 4. 201
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("postId", pid);
        result.put("category", category(category));
        result.put("title", title);
        result.put("createdAt", iso(created));
        return ResponseEntity.status(201)
                .header(HttpHeaders.LOCATION, "/forum/" + pid)
                .body(body("FORUM_POST_CREATED", "커뮤니티 게시글이 등록되었습니다.", result));
    }

    /** 요청 본문. 이 세 필드 외 값(작성자 ID 등)은 받지 않는다 */
    public static class PostRequest {
        public String category;
        public String title;
        public String content;
    }
}
