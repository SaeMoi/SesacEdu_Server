package com.example.sesacedu.mypage.cstqna;

import com.example.sesacedu.global.JwtUtil;
import com.example.sesacedu.global.JwtUtil.JwtAuthException;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.example.sesacedu.global.ApiResponse.*;

/**
 * 고객센터 1:1 문의(inquiries) 컨트롤러들이 같이 쓰는 헬퍼.
 *
 * - status는 inquiries.answer IS NULL이면 PENDING, 아니면 ANSWERED. editable은 PENDING일 때만 true.
 * - 답변 등록·수정은 관리자 서버(/admin/qna/{iqid}/answer)가 같은 inquiries 행에 한다.
 * - 쓰기(등록·수정·삭제)는 users.status를 DB에서 다시 본다. 제한 계정은 로그인·재발급에서 막히지만,
 *   로그인 중 제한되면 이미 받은 JWT가 만료 전까지 남기 때문 (Kim 2026-10-22: 제한 계정은 작성 불가).
 */
final class CstQnaSupport {

    static final int TITLE_MAX = 200;      // inquiries.title VARCHAR2(200 CHAR)
    static final int CONTENT_MAX = 4000;

    private CstQnaSupport() {
    }

    /** JWT 검증 + 회원 상태 확인. 회원 없음 401 UNAUTHORIZED, restricted 403 ACCOUNT_RESTRICTED (MypageExceptionHandler가 응답) */
    static long requireActiveMember(JwtUtil jwtUtil, JdbcTemplate jdbc, HttpServletRequest request) {
        long userId = jwtUtil.getUid(request);
        List<String> status = jdbc.queryForList("SELECT status FROM users WHERE user_id = ?", String.class, userId);
        if (status.isEmpty()) {
            throw new JwtAuthException(401, "UNAUTHORIZED", "인증 정보가 올바르지 않습니다.");
        }
        if ("restricted".equals(status.get(0))) {
            throw new JwtAuthException(403, "ACCOUNT_RESTRICTED", "이용이 제한된 계정입니다.");
        }
        return userId;
    }

    /** 양의 정수면 값, 아니면 null */
    static Long parseIqid(String v) {
        if (isBlank(v)) return null;
        try {
            long n = Long.parseLong(v.trim());
            return n > 0 ? n : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static ResponseEntity<Map<String, Object>> invalidIqid() {
        return invalidInput(Collections.singletonList(error("iqid", "양의 정수여야 합니다.")));
    }

    /** 제목 1~200자, 내용 1~4000자 (앞뒤 공백 제외). 오류 목록, 비었으면 통과 */
    static List<Map<String, String>> validate(InquiryRequest req) {
        List<Map<String, String>> errors = new ArrayList<>();
        String title = req == null || req.title == null ? "" : req.title.trim();
        String content = req == null || req.content == null ? "" : req.content.trim();
        if (title.isEmpty()) errors.add(error("title", "제목을 입력해주세요."));
        else if (title.length() > TITLE_MAX) errors.add(error("title", "제목은 " + TITLE_MAX + "자 이하여야 합니다."));
        if (content.isEmpty()) errors.add(error("content", "내용을 입력해주세요."));
        else if (content.length() > CONTENT_MAX) errors.add(error("content", "내용은 " + CONTENT_MAX + "자 이하여야 합니다."));
        return errors;
    }

    static ResponseEntity<Map<String, Object>> notFound() {
        return ResponseEntity.status(404).body(body("INQUIRY_NOT_FOUND", "문의를 찾을 수 없습니다.", null));
    }

    /** action: "수정" / "삭제" */
    static ResponseEntity<Map<String, Object>> alreadyAnswered(String action) {
        return ResponseEntity.status(409)
                .body(body("INQUIRY_ALREADY_ANSWERED", "답변이 달린 문의는 " + action + "할 수 없습니다.", null));
    }

    /** 상세 응답 result. 내 문의가 아니거나 없으면 null */
    static Map<String, Object> findDetail(JdbcTemplate jdbc, long iqid, long userId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT i.iqid, i.title, i.content, i.answer, i.answered_at, i.created, i.updated,"
                        + "       a.nickname AS answered_by_nickname"
                        + "  FROM inquiries i"
                        + "  LEFT JOIN users a ON a.user_id = i.answered_by"
                        + " WHERE i.iqid = ? AND i.user_id = ?",
                iqid, userId);
        if (rows.isEmpty()) return null;
        Map<String, Object> r = rows.get(0);

        boolean pending = r.get("ANSWER") == null;
        Map<String, Object> answeredBy = null;
        if (!pending && r.get("ANSWERED_BY_NICKNAME") != null) {
            answeredBy = new LinkedHashMap<>();
            answeredBy.put("nickname", r.get("ANSWERED_BY_NICKNAME"));   // 관리자 실명(uname)은 내보내지 않는다
        }
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("inquiryId", num(r.get("IQID")));
        d.put("title", r.get("TITLE"));
        d.put("content", r.get("CONTENT"));
        d.put("status", pending ? "PENDING" : "ANSWERED");
        d.put("createdAt", iso(r.get("CREATED")));
        d.put("updatedAt", iso(r.get("UPDATED")));
        d.put("answer", r.get("ANSWER"));
        d.put("answeredBy", answeredBy);
        d.put("answeredAt", pending ? null : iso(r.get("ANSWERED_AT")));
        d.put("editable", pending);
        return d;
    }

    /** 요청 본문 { title, content }. 이 두 필드 외 값(작성자 ID, answer 등)은 받지 않는다 */
    public static class InquiryRequest {
        public String title;
        public String content;
    }
}
