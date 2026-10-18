package com.example.sesacedu.forum;

import com.example.sesacedu.global.JwtUtil;
import com.example.sesacedu.global.JwtUtil.JwtAuthException;
import org.jsoup.Jsoup;
import org.jsoup.safety.Safelist;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.servlet.http.HttpServletRequest;
import java.sql.Timestamp;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 커뮤니티(/forum) 컨트롤러 공통 헬퍼.
 *
 * - posts.category 저장값은 QUESTION / STUDY / PROJECT 그대로 (Kim 확정 2026-10-14, 2026-10-16 프론트 탭에 맞춰 FREE→PROJECT). ALL·REVIEW는 조회 옵션일 뿐 저장하지 않는다.
 * - 글 본문은 HTML. 저장 전에 jsoup Safelist로 허용 서식만 남기고, 목록 excerpt는 jsoup으로 텍스트만 뽑는다.
 * - 쓰기 API(글·댓글 작성/삭제)는 토큰 sub의 회원을 DB에서 다시 읽어 없으면 401, restricted면 403 ACCOUNT_RESTRICTED.
 *   관리자 여부도 토큰 role이 아니라 DB의 users.auth(3)로 판단한다.
 */
public final class ForumSupport {

    public static final int ADMIN = 3;
    public static final int EXCERPT_MAX = 150;

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");

    /** 글 본문에 남길 서식. basic = p, br, b, strong, i, em, u, a(http/https/mailto), ul, ol, li, blockquote, code, pre 등 */
    private static final Safelist CONTENT_SAFELIST = Safelist.basic().addTags("s", "h2", "h3");

    /** 카테고리 코드 → 화면 이름 (목록·상세·작성 응답의 category.name) */
    static final Map<String, String> CATEGORY_NAMES = new LinkedHashMap<>();
    static {
        CATEGORY_NAMES.put("QUESTION", "질문·답변");
        CATEGORY_NAMES.put("STUDY", "스터디");
        CATEGORY_NAMES.put("PROJECT", "팀 프로젝트");
        CATEGORY_NAMES.put("REVIEW", "수강평");
    }

    private ForumSupport() {
    }

    // ================================================================ 회원

    /**
     * 쓰기 API 1단계: JWT 검증(없음·위조 401 UNAUTHORIZED, 만료 401 TOKEN_EXPIRED) → DB 회원 확인.
     * 회원 없음 401 UNAUTHORIZED, status = restricted면 403 ACCOUNT_RESTRICTED.
     */
    static Member requireActiveMember(JwtUtil jwtUtil, JdbcTemplate jdbc, HttpServletRequest request) {
        long userId = jwtUtil.getUid(request);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT auth, status FROM users WHERE user_id = ?", userId);
        if (rows.isEmpty()) {
            throw new JwtAuthException(401, "UNAUTHORIZED", "인증 정보가 올바르지 않습니다.");
        }
        Map<String, Object> r = rows.get(0);
        if ("restricted".equals(r.get("STATUS"))) {
            throw new ForumException(403, "ACCOUNT_RESTRICTED", "이용이 제한된 계정입니다.");
        }
        return new Member(userId, (int) num(r.get("AUTH")));
    }

    /**
     * 비로그인 허용 API(상세)의 isMine 계산용. 토큰이 없거나 위조·만료면 null(= 비로그인 취급)이고 에러를 내지 않는다.
     */
    static Long optionalUid(JwtUtil jwtUtil, HttpServletRequest request) {
        String token = jwtUtil.resolveToken(request);
        if (token == null) return null;
        try {
            return jwtUtil.getUid(token);
        } catch (JwtAuthException e) {
            return null;
        }
    }

    static final class Member {
        final long userId;
        final int auth;

        Member(long userId, int auth) {
            this.userId = userId;
            this.auth = auth;
        }

        boolean isAdmin() {
            return auth == ADMIN;
        }
    }

    // ================================================================ 본문 처리

    /** 허용 서식만 남긴 HTML (posts.content 저장값) */
    static String sanitizeHtml(String html) {
        return Jsoup.clean(html, CONTENT_SAFELIST);
    }

    /** HTML의 보이는 텍스트. script/style 내용 제외, 엔티티 디코딩, 공백·줄바꿈·nbsp를 한 칸으로 */
    static String htmlText(String html) {
        if (html == null || html.isEmpty()) return "";
        return collapse(Jsoup.parse(html).text());
    }

    /** 일반 텍스트(수강평)는 서식 파싱 없이 공백만 정리 */
    static String collapse(String s) {
        if (s == null) return "";
        return s.replace(' ', ' ').replaceAll("\\s+", " ").trim();
    }

    /** 최대 150자(말줄임표 포함). 넘으면 앞 149자 + "…". 코드포인트 기준이라 이모지를 쪼개지 않는다 */
    static String excerpt(String text) {
        if (text == null) return "";
        int len = text.codePointCount(0, text.length());
        if (len <= EXCERPT_MAX) return text;
        int end = text.offsetByCodePoints(0, EXCERPT_MAX - 1);
        return text.substring(0, end) + "…";
    }

    static int length(String s) {
        return s.codePointCount(0, s.length());
    }

    // ================================================================ 응답·입력 공통

    static Map<String, Object> category(String code) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("code", code);
        c.put("name", CATEGORY_NAMES.get(code));
        return c;
    }

    static Map<String, Object> pageInfo(int number, int size, long total) {
        long totalPages = (total + size - 1) / size;
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("number", number);
        p.put("size", size);
        p.put("totalElements", total);
        p.put("totalPages", totalPages);
        p.put("hasNext", number + 1 < totalPages);
        return p;
    }

    /** 비었으면 기본값, 정수가 아니면 null */
    static Integer parseInt(String v, int defaultValue) {
        if (isBlank(v)) return defaultValue;
        try {
            return Integer.valueOf(v.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 양의 정수(Long)면 그 값, 비었거나 아니면 null */
    static Long parsePositiveLong(String v) {
        if (isBlank(v)) return null;
        try {
            long n = Long.parseLong(v.trim());
            return n > 0 ? n : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static String iso(Object ts) {
        return ts instanceof Timestamp ? ((Timestamp) ts).toInstant().atZone(KST).format(ISO) : null;
    }

    static long num(Object n) {
        return n == null ? 0 : ((Number) n).longValue();
    }

    static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    static Map<String, String> error(String field, String reason) {
        Map<String, String> e = new LinkedHashMap<>();
        e.put("field", field);
        e.put("reason", reason);
        return e;
    }

    static ResponseEntity<Map<String, Object>> invalidInput(List<Map<String, String>> errors) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("errors", errors);
        return ResponseEntity.badRequest().body(body("INVALID_INPUT", "입력값을 확인해주세요.", result));
    }

    static Map<String, Object> body(String code, String message, Object result) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("code", code);
        b.put("message", message);
        b.put("result", result);
        return b;
    }

    /** 403·404 등 { code, message, result: null } 로 끝나는 실패. ForumExceptionHandler가 응답으로 바꾼다 */
    public static class ForumException extends RuntimeException {
        private final int status;
        private final String code;

        public ForumException(int status, String code, String message) {
            super(message);
            this.status = status;
            this.code = code;
        }

        public int getStatus() { return status; }
        public String getCode() { return code; }
    }
}
