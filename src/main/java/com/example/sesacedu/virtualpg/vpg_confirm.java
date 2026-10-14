package com.example.sesacedu.virtualpg;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

import static com.example.sesacedu.global.ApiResponse.body;

/**
 * POST /virtualPG/buy/confirm (Notion API 명세서: 결제 승인(back ↔ PG사)). 실제 PG사 대신 쓰는 가상 PG의 승인 API.
 *
 * 처리 순서: 1. 호출 주체 확인 (X-VPG-Secret 상수 시간 비교, 브라우저가 붙이는 Origin 헤더가 있으면 거부 → 401 VPG_UNAUTHORIZED,
 *      보안 로그에 IP) → 2. 본문 검증 (400 VPG_INVALID_REQUEST, CSV 기록 없음)
 * → 3. merchantOrderId별 잠금 + 메모리 인덱스: 이미 승인된 주문이면 같은 금액은 저장 결과 그대로 200, 다른 금액은 409 VPG_DUPLICATE_ORDER
 * → 4. 가상 승인 규칙 (Luhn → 유효기간 → 은행 코드 → 한도 → 테스트 번호 0002/0003/0009)
 * → 5. CSV 한 행 쓰기 성공 뒤 인덱스 저장 (실패 500 VPG_INTERNAL_ERROR) → 6. 200 VPG_APPROVED / VPG_DECLINED.
 *
 * 백엔드(POST /payment)만 서버끼리 부른다. Access Token은 보지 않는다 (mypage 예외 처리기 밖 패키지).
 * 시크릿 검사를 본문 해석보다 먼저 하려고 본문을 문자열로 받아 직접 파싱한다.
 * 이 API는 DB를 건드리지 않는다: 여기서 승인이 나도 주문을 paid로 바꾸는 것은 POST /payment뿐이고,
 * /payment는 금액을 orders.Aprice로만 보내고 응답 금액·주문번호를 다시 대조한다.
 *
 * 명세와 다르게 정한 것:
 * - amount 1,000,000 초과는 400이 아니라 승인 규칙 4번(LIMIT_EXCEEDED 거절)으로 보낸다 (두 규칙이 겹쳐서 4번이 죽은 규칙이 됨).
 * - 멱등은 승인된 주문에만 건다. 거절 결과는 CSV에 남기지만 같은 주문의 재요청은 다시 판단한다 (VpgLedger 주석 참고).
 * - 결제 정보 원문(카드 전체·CVC·유효기간·계좌 전체)은 응답·CSV·로그 어디에도 없다.
 */
@RestController
public class vpg_confirm {

    private static final Logger log = LoggerFactory.getLogger(vpg_confirm.class);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** 금융결제원 은행 코드 (가상 PG가 받는 목록) */
    static final Set<String> BANK_CODES = new HashSet<>(Arrays.asList(
            "002", "003", "004", "007", "011", "020", "023", "027", "031", "032", "034", "035", "037", "039",
            "045", "048", "071", "081", "088", "089", "090", "092"));

    private final VpgSecret secret;
    private final VpgLedger ledger;
    private final ObjectMapper mapper;
    private final long limitAmount;
    private final long slowDelayMs;

    public vpg_confirm(VpgSecret secret, VpgLedger ledger, ObjectMapper mapper,
                       @Value("${vpg.limit-amount:1000000}") long limitAmount,
                       @Value("${vpg.slow-delay-ms:15000}") long slowDelayMs) {
        this.secret = secret;
        this.ledger = ledger;
        this.mapper = mapper;
        this.limitAmount = limitAmount;
        this.slowDelayMs = slowDelayMs;
    }

    @PostMapping("/virtualPG/buy/confirm")
    public ResponseEntity<Map<String, Object>> confirm(@RequestBody(required = false) String raw,
                                                       HttpServletRequest request) {
        ZonedDateTime requestedAt = ZonedDateTime.now(KST);

        // 1. 호출 주체: 서버끼리 호출에는 Origin이 없다. 브라우저 fetch/form POST는 항상 Origin을 붙인다
        if (request.getHeader(HttpHeaders.ORIGIN) != null || !secret.matches(request.getHeader("X-VPG-Secret"))) {
            log.warn("VPG 인증 실패 ip={} origin={}", request.getRemoteAddr(), request.getHeader(HttpHeaders.ORIGIN) != null);
            return ResponseEntity.status(401).body(body("VPG_UNAUTHORIZED", "인증되지 않은 요청입니다.", null));
        }

        // 2. 본문 검증
        Req req = parse(raw);
        if (req == null) {
            return ResponseEntity.badRequest().body(body("VPG_INVALID_REQUEST", "요청 형식이 올바르지 않습니다.", null));
        }

        // 3. 주문별 잠금. 두 번째 요청은 첫 요청이 끝날 때까지 기다렸다가 저장 결과를 받는다
        ReentrantLock lock = ledger.lockFor(req.merchantOrderId);
        lock.lock();
        try {
            VpgLedger.Entry done = ledger.approvedEntry(req.merchantOrderId);
            if (done != null) {
                if (done.amount != req.amount) {
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("merchantOrderId", req.merchantOrderId);
                    return ResponseEntity.status(409).body(body("VPG_DUPLICATE_ORDER", "이미 처리된 주문 번호입니다.", result));
                }
                return ResponseEntity.ok(body("VPG_APPROVED", "승인되었습니다.", result(done)));
            }

            // 4. 가상 승인 규칙
            String reason = decide(req, requestedAt);
            if (reason == null && "CARD".equals(req.method) && req.cardNumber.endsWith("0009")) {
                sleep(slowDelayMs);   // 백엔드 읽기 타임아웃(502) 경로 테스트용. 처리는 승인으로 기록
            }

            // 5. CSV 기록 → 인덱스
            VpgLedger.Entry e = new VpgLedger.Entry();
            e.merchantOrderId = req.merchantOrderId;
            e.status = reason == null ? "APPROVED" : "DECLINED";
            e.amount = req.amount;
            e.method = req.method;
            if ("CARD".equals(req.method)) {
                e.cardBrand = brand(req.cardNumber);
                e.cardFirst4 = req.cardNumber.substring(0, 4);
                e.cardLast4 = req.cardNumber.substring(req.cardNumber.length() - 4);
                e.installment = req.installment;
            } else {
                e.bankCode = req.bankCode;
                e.accountLast4 = req.accountNumber.substring(req.accountNumber.length() - 4);
            }
            e.reasonCode = reason;
            e.requestedAt = requestedAt.format(ISO);
            e.approvedAt = reason == null ? ZonedDateTime.now(KST).format(ISO) : null;
            e.requestId = requestId(request.getHeader("X-Request-Id"));
            try {
                ledger.append(e, requestedAt.format(DAY));
            } catch (IOException ex) {
                log.error("VPG 원장 쓰기 실패 merchantOrderId={}", req.merchantOrderId, ex);
                return ResponseEntity.status(500).body(body("VPG_INTERNAL_ERROR", "가상 PG 내부 오류입니다.", null));
            }

            // 6. 응답
            return reason == null
                    ? ResponseEntity.ok(body("VPG_APPROVED", "승인되었습니다.", result(e)))
                    : ResponseEntity.ok(body("VPG_DECLINED", "승인이 거절되었습니다.", result(e)));
        } finally {
            lock.unlock();
        }
    }

    /** 판단 순서대로 처음 걸린 거절 사유, 승인이면 null */
    String decide(Req req, ZonedDateTime now) {
        if ("CARD".equals(req.method)) {
            if (!luhn(req.cardNumber)) return "INVALID_CARD";
            if (req.expiry.isBefore(YearMonth.from(now))) return "EXPIRED_CARD";
        } else if (!BANK_CODES.contains(req.bankCode)) {
            return "INVALID_ACCOUNT";
        }
        if (req.amount > limitAmount) return "LIMIT_EXCEEDED";
        String last4 = "CARD".equals(req.method) ? req.cardNumber.substring(req.cardNumber.length() - 4)
                : req.accountNumber.substring(req.accountNumber.length() - 4);
        if ("0002".equals(last4)) return "INSUFFICIENT_BALANCE";
        if ("CARD".equals(req.method) && "0003".equals(last4)) return "LIMIT_EXCEEDED";
        return null;
    }

    /** 검증된 요청. 결제 정보 원문은 이 객체에만 잠깐 있고 저장하지 않는다 */
    static class Req {
        String merchantOrderId;
        long amount;
        String orderName;
        String method;
        String cardNumber;
        YearMonth expiry;
        int installment;
        String bankCode;
        String accountNumber;
        String holderName;
    }

    /** 형식이 하나라도 틀리면 null (400). method와 맞지 않는 결제 정보가 같이 와도 400 */
    Req parse(String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;
        Map<?, ?> m;
        try {
            Object v = mapper.readValue(raw, Object.class);
            if (!(v instanceof Map)) return null;
            m = (Map<?, ?>) v;
        } catch (IOException e) {
            return null;
        }
        Req r = new Req();
        Object oid = m.get("merchantOrderId");
        if (!(oid instanceof String) || !((String) oid).matches("[0-9]{1,19}")) return null;
        r.merchantOrderId = (String) oid;
        Object amount = m.get("amount");
        if (!(amount instanceof Integer || amount instanceof Long) || ((Number) amount).longValue() < 1) return null;
        r.amount = ((Number) amount).longValue();
        Object name = m.get("orderName");
        if (!(name instanceof String) || ((String) name).trim().isEmpty() || ((String) name).length() > 100) return null;
        r.orderName = (String) name;
        Object method = m.get("method");
        if (!"CARD".equals(method) && !"TRANSFER".equals(method)) return null;
        r.method = (String) method;

        Object card = m.get("card");
        Object transfer = m.get("transfer");
        if ("CARD".equals(r.method)) {
            if (!(card instanceof Map) || transfer != null) return null;
            Map<?, ?> c = (Map<?, ?>) card;
            if (!str(c.get("number"), "[0-9]{14,16}") || !str(c.get("expiry"), "(0[1-9]|1[0-2])/[0-9]{2}")
                    || !str(c.get("cvc"), "[0-9]{3}")) return null;
            r.cardNumber = (String) c.get("number");
            String exp = (String) c.get("expiry");
            r.expiry = YearMonth.of(2000 + Integer.parseInt(exp.substring(3)), Integer.parseInt(exp.substring(0, 2)));
            Integer inst = installment(c.get("installment"));
            if (inst == null) return null;
            r.installment = inst;
        } else {
            if (!(transfer instanceof Map) || card != null) return null;
            Map<?, ?> t = (Map<?, ?>) transfer;
            if (!str(t.get("bankCode"), "[0-9]{3}") || !str(t.get("accountNumber"), "[0-9]{10,14}")) return null;
            Object holder = t.get("holderName");
            if (!(holder instanceof String) || ((String) holder).trim().isEmpty() || ((String) holder).length() > 20) return null;
            r.bankCode = (String) t.get("bankCode");
            r.accountNumber = (String) t.get("accountNumber");
            r.holderName = (String) holder;
        }
        return r;
    }

    /** 0(일시불) 또는 2~12, 없으면 0. 아니면 null */
    static Integer installment(Object v) {
        if (v == null) return 0;
        if (!(v instanceof Integer)) return null;
        int n = (Integer) v;
        return n == 0 || (n >= 2 && n <= 12) ? n : null;
    }

    static boolean luhn(String digits) {
        int sum = 0;
        for (int i = 0; i < digits.length(); i++) {
            int d = digits.charAt(digits.length() - 1 - i) - '0';
            if (i % 2 == 1) {
                d *= 2;
                if (d > 9) d -= 9;
            }
            sum += d;
        }
        return sum % 10 == 0;
    }

    static String brand(String number) {
        switch (number.charAt(0)) {
            case '4': return "VISA";
            case '5': return "MASTER";
            case '3': return "AMEX";
            default: return "ETC";
        }
    }

    static Map<String, Object> result(VpgLedger.Entry e) {
        boolean card = "CARD".equals(e.method);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("approvalId", e.approvalId);
        r.put("merchantOrderId", e.merchantOrderId);
        r.put("status", e.status);
        r.put("amount", e.amount);
        r.put("method", e.method);
        r.put("cardMasked", card ? (e.cardFirst4 == null ? "****" : e.cardFirst4) + "-****-****-" + e.cardLast4 : null);
        r.put("cardBrand", card ? e.cardBrand : null);
        r.put("installment", card ? e.installment : null);
        r.put("bankCode", card ? null : e.bankCode);
        r.put("accountLast4", card ? null : e.accountLast4);
        r.put("reasonCode", e.reasonCode);
        r.put("approvedAt", e.approvedAt);
        return r;
    }

    /** X-Request-Id는 추적용이라 100자로 자르고 줄바꿈은 뺀다 */
    static String requestId(String v) {
        if (v == null || v.trim().isEmpty()) return null;
        String s = v.replaceAll("[\\r\\n]", "").trim();
        return s.length() > 100 ? s.substring(0, 100) : s;
    }

    private static boolean str(Object v, String regex) {
        return v instanceof String && ((String) v).matches(regex);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
