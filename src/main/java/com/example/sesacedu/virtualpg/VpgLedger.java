package com.example.sesacedu.virtualpg;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 가상 PG CSV 원장 (Notion 결제 승인(back ↔ PG사)의 「CSV 원장」). 승인 기록의 유일한 원본이다.
 *
 * - 경로: vpg.ledger-path (기본 ./vpg/approvals.csv). 웹 루트·정적 리소스 밖. 처음 쓸 때 만들고, POSIX면 권한 600.
 * - UTF-8(BOM 없음), 줄바꿈 \n, 파일이 비어 있을 때만 헤더 한 번. 추가만 한다 (기존 행 수정·삭제 없음).
 * - 쓰기는 이 빈에서만: JVM 안은 ReentrantLock, 파일은 FileChannel.lock() + APPEND로 한 행 전체를 한 번에 쓰고 force(false).
 * - 카드·계좌 원문은 받지도 않는다. 남기는 것은 브랜드·뒤 4자리·은행 코드뿐.
 * - 값 이스케이프: 쉼표·큰따옴표·줄바꿈은 큰따옴표로 감싸고 " → "". =,+,-,@로 시작하면 앞에 ' (엑셀 수식 방지).
 *
 * 메모리 인덱스: 기동 시 CSV를 처음부터 읽어 merchantOrderId → 마지막 APPROVED 행을 채운다 (뒤에 CANCELED가 오면 뺀다).
 * DECLINED는 인덱스에 넣지 않는다: 결제 명세가 거절 주문을 pending으로 두고 다른 결제 정보로 재시도하게 하므로,
 * 거절 결과까지 멱등으로 돌려주면 재시도가 영원히 거절된다. 그래서 멱등은 「승인된 주문」에만 건다.
 * approvalId 순번은 날짜(KST)별이고, 기동 시 CSV에서 날짜별 마지막 번호를 읽어 이어 간다.
 */
@Component
public class VpgLedger {

    private static final Logger log = LoggerFactory.getLogger(VpgLedger.class);

    static final String HEADER = "approval_id,merchant_order_id,status,amount,currency,method,card_brand,card_last4,"
            + "installment,bank_code,account_last4,reason_code,requested_at,approved_at,request_id";

    private final Path path;
    private final ReentrantLock writeLock = new ReentrantLock();
    private final Map<String, Entry> approved = new ConcurrentHashMap<>();
    private final Map<String, ReentrantLock> orderLocks = new ConcurrentHashMap<>();
    private final Map<String, Long> lastSeq = new HashMap<>();   // yyyyMMdd → 마지막 순번 (writeLock 안에서만)

    public VpgLedger(@Value("${vpg.ledger-path:./vpg/approvals.csv}") String ledgerPath) throws IOException {
        this.path = Paths.get(ledgerPath).toAbsolutePath().normalize();
        load();
    }

    /** 원장 한 행 (카드는 뒤 4자리, 계좌는 뒤 4자리만) */
    public static class Entry {
        public String approvalId;
        public String merchantOrderId;
        public String status;            // APPROVED / DECLINED / CANCELED
        public long amount;
        public String method;            // CARD / TRANSFER
        public String cardBrand;
        public String cardFirst4;        // 응답의 cardMasked용. 메모리에만 있고 CSV에는 쓰지 않는다
        public String cardLast4;
        public Integer installment;
        public String bankCode;
        public String accountLast4;
        public String reasonCode;
        public String requestedAt;
        public String approvedAt;
        public String requestId;
    }

    /** 같은 merchantOrderId의 요청을 한 번에 하나씩 처리하기 위한 잠금 */
    public ReentrantLock lockFor(String merchantOrderId) {
        return orderLocks.computeIfAbsent(merchantOrderId, k -> new ReentrantLock());
    }

    /** 이미 승인된 주문이면 그 기록, 아니면 null */
    public Entry approvedEntry(String merchantOrderId) {
        return approved.get(merchantOrderId);
    }

    /** VPG-yyyyMMdd-nnnnnn 발급 + CSV에 한 행 쓰기. 쓰기에 성공한 뒤에만 인덱스에 넣는다 (실패하면 IOException) */
    public void append(Entry e, String yyyyMMdd) throws IOException {
        writeLock.lock();
        try {
            long seq = lastSeq.getOrDefault(yyyyMMdd, 0L) + 1;
            e.approvalId = String.format("VPG-%s-%06d", yyyyMMdd, seq);
            write(toLine(e));
            lastSeq.put(yyyyMMdd, seq);
            if ("APPROVED".equals(e.status)) approved.put(e.merchantOrderId, e);
        } finally {
            writeLock.unlock();
        }
    }

    public Path path() {
        return path;
    }

    private void write(String line) throws IOException {
        Path dir = path.getParent();
        if (dir != null) Files.createDirectories(dir);
        boolean created = !Files.exists(path);
        try (FileChannel ch = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
             FileLock ignored = ch.lock()) {
            if (created) restrictPermissions();
            String text = ch.size() == 0 ? HEADER + "\n" + line + "\n" : line + "\n";
            ByteBuffer buf = ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8));
            while (buf.hasRemaining()) ch.write(buf);
            ch.force(false);
        }
    }

    private void restrictPermissions() {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows 등 POSIX 권한이 없는 파일 시스템
        }
    }

    private void load() throws IOException {
        if (!Files.exists(path)) return;
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isEmpty() || (i == 0 && line.startsWith("approval_id,"))) continue;
            List<String> c = parseLine(line);
            if (c.size() < 15) {
                log.warn("VPG 원장 {}행 형식이 맞지 않아 건너뜁니다.", i + 1);
                continue;
            }
            Entry e = new Entry();
            e.approvalId = c.get(0);
            e.merchantOrderId = c.get(1);
            e.status = c.get(2);
            e.amount = parseLong(c.get(3));
            e.method = c.get(5);
            e.cardBrand = blankToNull(c.get(6));
            e.cardLast4 = blankToNull(c.get(7));
            e.installment = c.get(8).isEmpty() ? null : (int) parseLong(c.get(8));
            e.bankCode = blankToNull(c.get(9));
            e.accountLast4 = blankToNull(c.get(10));
            e.reasonCode = blankToNull(c.get(11));
            e.requestedAt = c.get(12);
            e.approvedAt = blankToNull(c.get(13));
            e.requestId = blankToNull(c.get(14));
            if ("APPROVED".equals(e.status)) approved.put(e.merchantOrderId, e);
            else if ("CANCELED".equals(e.status)) approved.remove(e.merchantOrderId);
            String[] id = e.approvalId.split("-");
            if (id.length == 3) lastSeq.merge(id[1], parseLong(id[2]), Math::max);
        }
    }

    static String toLine(Entry e) {
        String[] v = {e.approvalId, e.merchantOrderId, e.status, String.valueOf(e.amount), "KRW", e.method,
                e.cardBrand, e.cardLast4, e.installment == null ? null : String.valueOf(e.installment),
                e.bankCode, e.accountLast4, e.reasonCode, e.requestedAt, e.approvedAt, e.requestId};
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < v.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(escape(v[i]));
        }
        return sb.toString();
    }

    static String escape(String v) {
        if (v == null) return "";
        if (!v.isEmpty() && "=+-@".indexOf(v.charAt(0)) >= 0) v = "'" + v;
        if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r")) {
            v = "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }

    static List<String> parseLine(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (quoted) {
                if (ch == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cur.append('"');
                    i++;
                } else if (ch == '"') {
                    quoted = false;
                } else {
                    cur.append(ch);
                }
            } else if (ch == '"') {
                quoted = true;
            } else if (ch == ',') {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(ch);
            }
        }
        out.add(cur.toString());
        return out;
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }
}
