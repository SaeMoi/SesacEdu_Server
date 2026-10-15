package com.example.sesacedu.auth;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 이메일 인증번호 저장소 (Notion API 명세서: 이메일 중복 및 인증번호 요청 「저장 방식」).
 *
 * DB·Redis·세션 없이 서버 메모리 맵 하나에 둔다. email_auth(발송), email_auth_verify(확인), signup(가입)이
 * 이 빈 하나를 같이 쓴다. 단일 서버에서만 동작하고 서버를 재시작하면 진행 중이던 인증이 모두 사라진다.
 *
 * - 인증번호: 6자리, 발송 후 5분 유효. 재발송하면 이전 번호는 무효.
 * - verificationToken: 확인 성공 시 발급(랜덤 32자), 30분 유효, 가입 성공 시 entry를 지워 1회용.
 * - 발송 제한(429): 같은 이메일 60초 내 재요청 / 이메일당 1시간 5회 / IP당 1시간 20회.
 * - 확인 횟수 제한 없음 (의도된 취약점): tries에 쌓기만 하고 막지 않는다.
 */
@Service
@EnableScheduling
public class EmailAuthStore {

    public static final Duration CODE_TTL = Duration.ofMinutes(5);
    public static final Duration TOKEN_TTL = Duration.ofMinutes(30);
    public static final Duration RESEND_AFTER = Duration.ofSeconds(60);
    private static final Duration WINDOW = Duration.ofHours(1);
    private static final int MAX_PER_EMAIL = 5;
    private static final int MAX_PER_IP = 20;

    /** 확인 결과 */
    public enum VerifyResult { VERIFIED, INVALID_CODE, CODE_EXPIRED }

    // key = 소문자 이메일
    private final Map<String, Entry> store = new ConcurrentHashMap<>();
    // IP당 1시간 20회 판단용 (이메일과 별개)
    private final Map<String, List<Instant>> sendTimesByIp = new ConcurrentHashMap<>();

    private final SecureRandom random = new SecureRandom();   // java.util.Random은 다음 값 예측 가능
    private final Clock clock;

    static class Entry {
        String code;                 // 6자리. 발송 실패 시 null (번호 저장 안 함)
        Instant expiresAt;           // 발송 + 5분
        int tries;                   // 틀린 횟수 (기록만, 막지 않음 = 의도된 취약점)
        String verificationToken;    // 인증 성공 시 발급 (랜덤 32자)
        Instant verifiedAt;          // 인증 성공 시각 (이후 30분간 가입 가능)
        final List<Instant> sendTimes = new ArrayList<>();  // 429 판단용 (이메일당 1시간 5회)
    }

    /** Clock 빈이 있으면 그것을 쓴다 (테스트에서 시간 이동용). 없으면 시스템 시계. */
    public EmailAuthStore(ObjectProvider<Clock> clock) {
        this.clock = clock.getIfAvailable(Clock::systemUTC);
    }

    public Instant now() {
        return clock.instant();
    }

    /** 발송 가능하면 0, 아니면 다시 시도할 수 있을 때까지 남은 초 (429 retryAfter) */
    public synchronized long retryAfterSeconds(String email, String ip) {
        Instant now = now();
        long wait = 0;
        Entry e = store.get(email);
        if (e != null) {
            List<Instant> recent = recent(e.sendTimes, now);
            if (!recent.isEmpty()) {
                Instant last = recent.get(recent.size() - 1);
                wait = Math.max(wait, secondsUntil(last.plus(RESEND_AFTER), now));
            }
            if (recent.size() >= MAX_PER_EMAIL) {
                wait = Math.max(wait, secondsUntil(recent.get(recent.size() - MAX_PER_EMAIL).plus(WINDOW), now));
            }
        }
        List<Instant> byIp = recent(sendTimesByIp.getOrDefault(ip, new ArrayList<>()), now);
        if (byIp.size() >= MAX_PER_IP) {
            wait = Math.max(wait, secondsUntil(byIp.get(byIp.size() - MAX_PER_IP).plus(WINDOW), now));
        }
        return wait;
    }

    /**
     * 이 이메일의 기존 entry를 버리고 새 번호로 entry를 만든다 (이전 번호·토큰 무효). 발송 이력(sendTimes)만 이어받는다.
     * 메일 발송 전에 부르고, 발송에 실패하면 {@link #sendFailed(String)}를 부른다.
     */
    public synchronized String issue(String email, String ip) {
        Instant now = now();
        Entry old = store.get(email);
        Entry e = new Entry();
        if (old != null) e.sendTimes.addAll(recent(old.sendTimes, now));
        e.code = String.format("%06d", random.nextInt(1_000_000));
        e.expiresAt = now.plus(CODE_TTL);
        e.sendTimes.add(now);
        store.put(email, e);
        sendTimesByIp.computeIfAbsent(ip, k -> new ArrayList<>()).add(now);
        return e.code;
    }

    /** 발송 실패: 이번 번호는 저장하지 않는다. 실패한 발송은 429 횟수에도 넣지 않는다. */
    public synchronized void sendFailed(String email, String ip) {
        Entry e = store.get(email);
        if (e != null) {
            e.code = null;
            e.expiresAt = null;
            if (!e.sendTimes.isEmpty()) e.sendTimes.remove(e.sendTimes.size() - 1);
        }
        List<Instant> byIp = sendTimesByIp.get(ip);
        if (byIp != null && !byIp.isEmpty()) byIp.remove(byIp.size() - 1);
    }

    /** 인증번호 확인. 성공하면 같은 entry에 verificationToken·verifiedAt을 저장한다. */
    public synchronized VerifyResult verify(String email, String code) {
        Instant now = now();
        Entry e = store.get(email);
        if (e == null || e.code == null || now.isAfter(e.expiresAt)) return VerifyResult.CODE_EXPIRED;
        if (!e.code.equals(code)) {
            e.tries++;                                   // 기록만 하고 막지 않음 (의도된 취약점)
            return VerifyResult.INVALID_CODE;
        }
        byte[] b = new byte[16];
        random.nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        e.verificationToken = sb.toString();
        e.verifiedAt = now;
        return VerifyResult.VERIFIED;
    }

    /** verify 성공 직후 발급된 토큰 */
    public synchronized String tokenOf(String email) {
        Entry e = store.get(email);
        return e == null ? null : e.verificationToken;
    }

    /** /signup: 이 이메일로 발급된 토큰이 맞고, 인증 후 30분 이내이며, 아직 가입에 쓰지 않았는지 */
    public synchronized boolean isVerified(String email, String verificationToken) {
        Entry e = store.get(email);
        if (e == null || e.verificationToken == null || e.verifiedAt == null || verificationToken == null) return false;
        if (now().isAfter(e.verifiedAt.plus(TOKEN_TTL))) return false;
        return MessageDigest.isEqual(e.verificationToken.getBytes(StandardCharsets.UTF_8),
                verificationToken.getBytes(StandardCharsets.UTF_8));
    }

    /** 가입 성공 후 entry 제거 (토큰 1회용) */
    public synchronized void consume(String email) {
        store.remove(email);
    }

    /** 틀린 횟수 (막지는 않음. 흔적 확인용) */
    public synchronized int triesOf(String email) {
        Entry e = store.get(email);
        return e == null ? 0 : e.tries;
    }

    /** 10분마다 만료·오래된 entry 제거 */
    @Scheduled(fixedDelay = 600_000)
    public synchronized void cleanup() {
        Instant now = now();
        for (Iterator<Map.Entry<String, Entry>> it = store.entrySet().iterator(); it.hasNext(); ) {
            Entry e = it.next().getValue();
            boolean codeAlive = e.expiresAt != null && !now.isAfter(e.expiresAt);
            boolean tokenAlive = e.verifiedAt != null && !now.isAfter(e.verifiedAt.plus(TOKEN_TTL));
            List<Instant> recent = recent(e.sendTimes, now);
            e.sendTimes.retainAll(recent);
            if (!codeAlive && !tokenAlive && recent.isEmpty()) it.remove();
        }
        for (Iterator<Map.Entry<String, List<Instant>>> it = sendTimesByIp.entrySet().iterator(); it.hasNext(); ) {
            List<Instant> times = it.next().getValue();
            times.retainAll(recent(times, now));
            if (times.isEmpty()) it.remove();
        }
    }

    private static List<Instant> recent(List<Instant> times, Instant now) {
        List<Instant> r = new ArrayList<>();
        Instant from = now.minus(WINDOW);
        for (Instant t : times) if (t.isAfter(from)) r.add(t);
        return r;
    }

    private static long secondsUntil(Instant t, Instant now) {
        long ms = Duration.between(now, t).toMillis();
        return ms <= 0 ? 0 : (ms + 999) / 1000;
    }
}
