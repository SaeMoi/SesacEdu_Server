package com.example.sesacedu;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.Map;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 강사 정산 신청 (POST /instructor/settlement) + 목록의 requestable.
 * 달은 실행 시점(KST) 기준으로 만든다: 이번 달 / 지난 달 / 2달 전 / 3달 전 / 4달 전.
 * H2(Oracle 모드, application-test.yml + schema-test.sql)로 실행:
 *   gradlew test --tests com.example.sesacedu.InstructorSettlementRequestTest
 */
@SuppressWarnings("null") // VS Code(JDT) null 분석이 Spring/MockMvc API에 내는 경고 제외. 실행에는 영향 없음
@SpringBootTest(properties = "JWT_SECRET=" + InstructorSettlementRequestTest.SECRET)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class InstructorSettlementRequestTest {

    static final String SECRET = "instructor-settlement-test-secret-at-least-32-bytes!!";
    static final DateTimeFormatter DOT = DateTimeFormatter.ofPattern("yyyy.MM");
    static final DateTimeFormatter DASH = DateTimeFormatter.ofPattern("yyyy-MM");

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    static final long A = 9101;   // 강사 A
    static final long B = 9102;   // 강사 B (데이터 분리 확인용)
    static final long BUYER = 9201;

    YearMonth now;
    YearMonth last;    // 지난 달: A 매출 30000 + 20000, B 매출 50000
    YearMonth m2;      // 2달 전: A 매출 10000, 기존 행 approved ("yyyy.MM" 옛 형식)
    YearMonth m3;      // 3달 전: A 매출 40000, 기존 행 rejected
    YearMonth m4;      // 4달 전: 매출 없음

    @BeforeEach
    void setUp() {
        for (String t : new String[]{"settlements", "order_items", "orders", "lectures", "instructors"}) {
            jdbc.update("DELETE FROM " + t);
        }
        now = YearMonth.now(ZoneId.of("Asia/Seoul"));
        last = now.minusMonths(1);
        m2 = now.minusMonths(2);
        m3 = now.minusMonths(3);
        m4 = now.minusMonths(4);

        jdbc.update("INSERT INTO instructors (user_id, iname, bank, account) VALUES (?, '강사A', '새싹은행', '111-222')", A);
        jdbc.update("INSERT INTO instructors (user_id, iname, bank, account) VALUES (?, '강사B', '다른은행', '999-888')", B);
        jdbc.update("INSERT INTO lectures (Lid, user_id, name, category, Lprice, status) VALUES (801, ?, 'A의 강의1', '웹', 30000, 'open')", A);
        jdbc.update("INSERT INTO lectures (Lid, user_id, name, category, Lprice, status) VALUES (802, ?, 'A의 강의2', '웹', 20000, 'open')", A);
        jdbc.update("INSERT INTO lectures (Lid, user_id, name, category, Lprice, status) VALUES (803, ?, 'B의 강의', '웹', 50000, 'open')", B);

        paid(801, 30000, last.atDay(1).atStartOfDay().toString());              // 지난 달 1일 00:00 (경계 포함)
        paid(802, 20000, last.atEndOfMonth().atTime(23, 59, 59).toString());   // 지난 달 말일 23:59:59 (경계 포함)
        paid(803, 50000, last.atDay(15).atTime(12, 0).toString());             // B
        paid(801, 10000, m2.atDay(10).atTime(9, 0).toString());
        paid(801, 40000, m3.atDay(10).atTime(9, 0).toString());
        paid(802, 70000, now.atDay(1).atTime(0, 0, 1).toString());             // 이번 달 → 신청 불가

        jdbc.update("INSERT INTO settlements (user_id, period, revenue, deduction, amount, status, requested, approved_at) "
                + "VALUES (?, ?, 10000, 2000, 8000, 'approved', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", A, m2.format(DOT));
        jdbc.update("INSERT INTO settlements (user_id, period, revenue, deduction, amount, status, requested, rejected_at, reject_reason) "
                + "VALUES (?, ?, 1, 0, 1, 'rejected', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, '계좌 확인 필요')", A, m3.format(DASH));
    }

    void paid(long lid, long price, String paidAt) {
        jdbc.update("INSERT INTO orders (user_id, status, paid_at) VALUES (?, 'paid', ?)",
                BUYER, Timestamp.valueOf(paidAt.replace('T', ' ') + (paidAt.length() == 16 ? ":00" : "")));
        Long oid = jdbc.queryForObject("SELECT MAX(oid) FROM orders", Long.class);
        jdbc.update("INSERT INTO order_items (oid, Lid, Lname, price) VALUES (?, ?, '강의', ?)", oid, lid, price);
    }

    static String bearer(long sub) {
        return "Bearer " + Jwts.builder().subject(String.valueOf(sub)).claim("role", "INSTRUCTOR").claim("reissue_num", 2)
                .expiration(new Date(System.currentTimeMillis() + 3_600_000))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)), Jwts.SIG.HS256).compact();
    }

    static String body(String period) {
        return "{\"period\":\"" + period + "\"}";
    }

    Map<String, Object> row(long uid, YearMonth ym) {
        return jdbc.queryForMap("SELECT * FROM settlements WHERE user_id = ? AND period IN (?, ?)", uid, ym.format(DASH), ym.format(DOT));
    }

    @Test @DisplayName("목록: 지난 달(신청 전)·반려 달만 requestable, 이번 달·승인 달은 false")
    void listRequestable() throws Exception {
        mvc.perform(get("/instructor/settlement").header("Authorization", bearer(A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.settlements[?(@.period == '" + now.format(DOT) + "')].requestable").value(contains(false)))
                .andExpect(jsonPath("$.settlements[?(@.period == '" + now.format(DOT) + "')].status").value(contains("pending")))
                .andExpect(jsonPath("$.settlements[?(@.period == '" + last.format(DOT) + "')].requestable").value(contains(true)))
                .andExpect(jsonPath("$.settlements[?(@.period == '" + last.format(DOT) + "')].amount").value(contains(40000)))
                .andExpect(jsonPath("$.settlements[?(@.period == '" + m2.format(DOT) + "')].requestable").value(contains(false)))
                .andExpect(jsonPath("$.settlements[?(@.period == '" + m3.format(DOT) + "')].requestable").value(contains(true)));
    }

    @Test @DisplayName("신청 201: 지난 달 → requested, period 'YYYY-MM' 저장, 금액 재계산(공제 20%), 계좌 복사, 월 경계 포함")
    void requestLastMonth() throws Exception {
        mvc.perform(post("/instructor/settlement").header("Authorization", bearer(A))
                        .contentType(MediaType.APPLICATION_JSON).content(body(last.format(DASH))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("requested"))
                .andExpect(jsonPath("$.period").value(last.format(DOT)))
                .andExpect(jsonPath("$.amount").value(40000))
                .andExpect(jsonPath("$.sid").value(notNullValue()));

        Map<String, Object> r = row(A, last);
        assertEquals(last.format(DASH), r.get("PERIOD"));
        assertEquals("requested", r.get("STATUS"));
        assertEquals(50000L, ((Number) r.get("REVENUE")).longValue());
        assertEquals(10000L, ((Number) r.get("DEDUCTION")).longValue());
        assertEquals(40000L, ((Number) r.get("AMOUNT")).longValue());
        assertEquals("새싹은행", r.get("BANK"));

        // 신청 후 목록에서는 requestable false
        mvc.perform(get("/instructor/settlement").header("Authorization", bearer(A)))
                .andExpect(jsonPath("$.settlements[?(@.period == '" + last.format(DOT) + "')].status").value(contains("requested")))
                .andExpect(jsonPath("$.settlements[?(@.period == '" + last.format(DOT) + "')].requestable").value(contains(false)));
    }

    @Test @DisplayName("신청 400: 이번 달 / 매출 없는 달 / 형식 오류 / 본문 없음")
    void requestInvalid() throws Exception {
        for (String p : new String[]{now.format(DASH), now.plusMonths(1).format(DASH), m4.format(DASH), "2026-13", "abc"}) {
            mvc.perform(post("/instructor/settlement").header("Authorization", bearer(A))
                            .contentType(MediaType.APPLICATION_JSON).content(body(p)))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(post("/instructor/settlement").header("Authorization", bearer(A))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        Long cnt = jdbc.queryForObject("SELECT COUNT(*) FROM settlements WHERE user_id = ?", Long.class, A);
        assertEquals(2L, cnt);   // 기존 2행 그대로
    }

    @Test @DisplayName("신청 409: 이미 신청한 달 다시 / 승인된 달(옛 'yyyy.MM' 행)")
    void requestDuplicate() throws Exception {
        mvc.perform(post("/instructor/settlement").header("Authorization", bearer(A))
                        .contentType(MediaType.APPLICATION_JSON).content(body(last.format(DASH))))
                .andExpect(status().isCreated());
        mvc.perform(post("/instructor/settlement").header("Authorization", bearer(A))
                        .contentType(MediaType.APPLICATION_JSON).content(body(last.format(DOT))))
                .andExpect(status().isConflict());
        mvc.perform(post("/instructor/settlement").header("Authorization", bearer(A))
                        .contentType(MediaType.APPLICATION_JSON).content(body(m2.format(DASH))))
                .andExpect(status().isConflict());
        Long cnt = jdbc.queryForObject("SELECT COUNT(*) FROM settlements WHERE user_id = ?", Long.class, A);
        assertEquals(3L, cnt);
    }

    @Test @DisplayName("재신청 201: 반려된 달 → 같은 행을 requested 로, 반려 사유 비우고 금액 재계산")
    void reRequestRejected() throws Exception {
        Object sid = row(A, m3).get("SID");
        mvc.perform(post("/instructor/settlement").header("Authorization", bearer(A))
                        .contentType(MediaType.APPLICATION_JSON).content(body(m3.format(DASH))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sid").value(((Number) sid).intValue()))
                .andExpect(jsonPath("$.amount").value(32000));
        Map<String, Object> r = row(A, m3);
        assertEquals("requested", r.get("STATUS"));
        assertNull(r.get("REJECT_REASON"));
        assertNull(r.get("REJECTED_AT"));
        assertEquals(40000L, ((Number) r.get("REVENUE")).longValue());
    }

    @Test @DisplayName("데이터 분리: B의 신청은 B 매출만, A의 목록·신청에 B 정산이 섞이지 않음")
    void isolation() throws Exception {
        mvc.perform(post("/instructor/settlement").header("Authorization", bearer(B))
                        .contentType(MediaType.APPLICATION_JSON).content(body(last.format(DASH))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.amount").value(40000));   // 50000 - 20%
        assertEquals(50000L, ((Number) row(B, last).get("REVENUE")).longValue());
        assertEquals("다른은행", row(B, last).get("BANK"));

        // B가 신청했어도 A의 지난 달은 여전히 신청 가능, A 신청은 A 매출만
        mvc.perform(get("/instructor/settlement").header("Authorization", bearer(A)))
                .andExpect(jsonPath("$.settlements[?(@.period == '" + last.format(DOT) + "')].requestable").value(contains(true)));
        mvc.perform(post("/instructor/settlement").header("Authorization", bearer(A))
                        .contentType(MediaType.APPLICATION_JSON).content(body(last.format(DASH))))
                .andExpect(status().isCreated());
        assertEquals(50000L, ((Number) row(A, last).get("REVENUE")).longValue());
    }
}
