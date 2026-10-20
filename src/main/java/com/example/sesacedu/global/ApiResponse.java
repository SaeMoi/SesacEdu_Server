package com.example.sesacedu.global;

import com.example.sesacedu.global.JwtUtil.JwtAuthException;
import org.springframework.http.ResponseEntity;

import java.sql.Timestamp;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 공통 응답 형식 { code, message, result }와 컨트롤러들이 같이 쓰는 작은 헬퍼.
 * 컨트롤러에서 import static com.example.sesacedu.global.ApiResponse.*; 로 쓴다.
 *
 * - body / error / invalidInput / jwtError : 응답 본문 만들기 (400 INVALID_INPUT은 result.errors[{ field, reason }])
 * - parseInt / num / iso / pageInfo / isBlank : 쿼리 파싱, DB 값 변환(KST ISO 8601), 페이지 정보
 */
public final class ApiResponse {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");

    private ApiResponse() {
    }

    public static Map<String, Object> body(String code, String message, Object result) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("code", code);
        b.put("message", message);
        b.put("result", result);
        return b;
    }

    public static Map<String, String> error(String field, String reason) {
        Map<String, String> e = new LinkedHashMap<>();
        e.put("field", field);
        e.put("reason", reason);
        return e;
    }

    /** 400 INVALID_INPUT "입력값을 확인해주세요." { errors } */
    public static ResponseEntity<Map<String, Object>> invalidInput(List<Map<String, String>> errors) {
        return invalidInput("입력값을 확인해주세요.", errors);
    }

    /** 400 INVALID_INPUT, 명세가 다른 message를 정한 경우 */
    public static ResponseEntity<Map<String, Object>> invalidInput(String message, List<Map<String, String>> errors) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("errors", errors);
        return ResponseEntity.badRequest().body(body("INVALID_INPUT", message, result));
    }

    /** JwtUtil이 던진 401 / 403 → { code, message, result: null } */
    public static ResponseEntity<Map<String, Object>> jwtError(JwtAuthException e) {
        return ResponseEntity.status(e.getStatus()).body(body(e.getCode(), e.getMessage(), null));
    }

    /** { number, size, totalElements, totalPages, hasNext } */
    public static Map<String, Object> pageInfo(int number, int size, long total) {
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
    public static Integer parseInt(String v, int defaultValue) {
        if (isBlank(v)) return defaultValue;
        try {
            return Integer.valueOf(v.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** DB TIMESTAMP → "2026-10-13T10:00:00+09:00", 아니면 null */
    public static String iso(Object ts) {
        return ts instanceof Timestamp ? ((Timestamp) ts).toInstant().atZone(KST).format(ISO) : null;
    }

    /** DB NUMBER → long, null이면 0 */
    public static long num(Object n) {
        return n == null ? 0 : ((Number) n).longValue();
    }

    public static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
