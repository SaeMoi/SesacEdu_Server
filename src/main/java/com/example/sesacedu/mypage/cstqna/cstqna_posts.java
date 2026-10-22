package com.example.sesacedu.mypage.cstqna;

import com.example.sesacedu.global.JwtUtil;
import com.example.sesacedu.mypage.cstqna.CstQnaSupport.InquiryRequest;
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
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.example.sesacedu.global.ApiResponse.*;

/**
 * POST /mypage/cstqna/posts (Notion API 명세서: 고객센터 1:1 문의 작성).
 *
 * 처리 순서: 1. JWT 검증 + DB 회원 확인 (401 UNAUTHORIZED / TOKEN_EXPIRED, restricted 403 ACCOUNT_RESTRICTED)
 * → 2. 입력 검증 (title 1~200자, content 1~4000자, 앞뒤 공백 제거. 아니면 400 INVALID_INPUT)
 * → 3. INSERT inquiries(user_id, title, content) → 4. 201 CST_QNA_CREATED + Location, result = 상세와 같은 모양.
 * 작성자는 토큰 sub로만 정한다. answer·answered_by 등은 요청에서 받지 않는다 (DTO에 필드 없음).
 */
@RestController
public class cstqna_posts {

    private final JdbcTemplate jdbc;
    private final JwtUtil jwtUtil;

    public cstqna_posts(JdbcTemplate jdbc, JwtUtil jwtUtil) {
        this.jdbc = jdbc;
        this.jwtUtil = jwtUtil;
    }

    @Transactional
    @PostMapping("/mypage/cstqna/posts")
    public ResponseEntity<Map<String, Object>> create(@RequestBody(required = false) InquiryRequest req,
                                                      HttpServletRequest request) {
        // 1. JWT + 회원 상태
        long userId = CstQnaSupport.requireActiveMember(jwtUtil, jdbc, request);

        // 2. 입력 검증
        List<Map<String, String>> errors = CstQnaSupport.validate(req);
        if (!errors.isEmpty()) return invalidInput(errors);
        String title = req.title.trim();
        String content = req.content.trim();

        // 3. 저장. iqid는 IDENTITY로 생성
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO inquiries (user_id, title, content, created) VALUES (?, ?, ?, SYSTIMESTAMP)",
                    new String[]{"IQID"});
            ps.setLong(1, userId);
            ps.setString(2, title);
            ps.setString(3, content);
            return ps;
        }, kh);
        long iqid = Objects.requireNonNull(kh.getKey()).longValue();

        // 4. 응답
        return ResponseEntity.status(201)
                .header(HttpHeaders.LOCATION, "/mypage/cstqna/" + iqid)
                .body(body("CST_QNA_CREATED", "1:1 문의가 등록되었습니다.", CstQnaSupport.findDetail(jdbc, iqid, userId)));
    }
}
