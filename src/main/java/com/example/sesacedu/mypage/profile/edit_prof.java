package com.example.sesacedu.mypage.profile;

import com.example.sesacedu.global.ApiResponse;
import com.example.sesacedu.global.JwtUtil;
import com.example.sesacedu.global.JwtUtil.JwtAuthException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.servlet.http.HttpServletRequest;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static com.example.sesacedu.global.ApiResponse.*;

/**
 * POST /mypage/edit_prof (Notion API 명세서: 프로필 사진 변경). multipart/form-data, 파트 이름 image.
 *
 * 처리 순서: 1. Authorization의 JWT 검증 (없음·위조 401 UNAUTHORIZED, 만료 401 TOKEN_EXPIRED)
 * → 2. 회원당 1시간 10회 초과면 429 TOO_MANY_REQUESTS { retryAfter }
 * → 3. image 파트 없음·빈 파일·2개 이상 400 INVALID_INPUT → 4. 5MB 초과 413 FILE_TOO_LARGE { maxBytes }
 * → 5. 매직 바이트가 JPEG(FF D8 FF)·PNG(89 50 4E 47)가 아니면 415 UNSUPPORTED_IMAGE_TYPE
 * → 6. 실제로 열어 보고 (가로·세로 4,096px 초과, 깨진 파일) 400 INVALID_IMAGE
 * → 7. 가운데를 정사각형으로 잘라 400×400 PNG로 다시 그려 {user_id}_profile.png에 덮어쓰기 (원본 바이트·EXIF 버림)
 * → 8. users.picture = 파일명 → 9. 200 PROFILE_IMAGE_UPDATED { profileImageUrl: /images/profile/{파일명}?v={epoch} }.
 * 클라이언트가 보낸 Content-Type·파일명은 쓰지 않는다. 회원은 토큰 sub로만 정한다.
 * 횟수 제한은 서버 메모리(서버 1대 기준, 재시작 시 초기화)이며 인증된 업로드 시도마다 1회로 센다.
 * 감사 로그는 audit_logs 보류 결정에 따라 쓰지 않는다.
 *
 * 의도된 취약점 (모의해킹용): 파일명이 {user_id}_profile.png로 예측 가능하고 /images/profile/ 경로가 공개라
 * 다른 회원의 사진을 번호로 열거할 수 있다 (공개 정보).
 */
@RestController
public class edit_prof {

    private static final Logger log = LoggerFactory.getLogger(edit_prof.class);

    static final long MAX_BYTES = 5L * 1024 * 1024;
    private static final int MAX_DIMENSION = 4096;
    private static final int OUT_SIZE = 400;
    private static final int LIMIT_PER_HOUR = 10;
    private static final long WINDOW_MS = 3600_000L;

    private final JdbcTemplate jdbc;
    private final JwtUtil jwtUtil;
    private final ProfileImageStore images;
    /** user_id → 최근 1시간 업로드 시각들 */
    private final Map<Long, Deque<Long>> attempts = new ConcurrentHashMap<>();

    public edit_prof(JdbcTemplate jdbc, JwtUtil jwtUtil, ProfileImageStore images) {
        this.jdbc = jdbc;
        this.jwtUtil = jwtUtil;
        this.images = images;
    }

    @PostMapping("/mypage/edit_prof")
    public ResponseEntity<Map<String, Object>> upload(@RequestParam(name = "image", required = false) List<MultipartFile> files,
                                                      HttpServletRequest request) throws IOException {
        // 1. JWT 검증
        long userId = jwtUtil.getUid(request);
        Integer exists = jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE user_id = ?", Integer.class, userId);
        if (exists == null || exists == 0) throw new JwtAuthException(401, "UNAUTHORIZED", "인증 정보가 올바르지 않습니다.");

        // 2. 회원당 1시간 10회
        long retryAfter = takeAttempt(userId);
        if (retryAfter > 0) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("retryAfter", retryAfter);
            return ResponseEntity.status(429).header(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfter))
                    .body(body("TOO_MANY_REQUESTS", "잠시 후 다시 시도해주세요.", result));
        }

        // 3. 파트 확인
        if (files == null || files.isEmpty() || files.get(0).isEmpty()) return invalidInput("이미지 파일을 선택해주세요.");
        if (files.size() > 1) return invalidInput("이미지는 1개만 올릴 수 있습니다.");
        MultipartFile file = files.get(0);

        // 4. 크기
        if (file.getSize() > MAX_BYTES) return fileTooLarge();
        byte[] bytes = file.getBytes();

        // 5. 형식은 파일 내용(매직 바이트)으로만 판단
        if (!isJpeg(bytes) && !isPng(bytes)) {
            return ResponseEntity.status(415).body(body("UNSUPPORTED_IMAGE_TYPE", "JPG, PNG 이미지만 올릴 수 있습니다.", null));
        }

        // 6. 실제로 열어 보기 (크기는 디코딩 전에 확인해 거대 이미지로 메모리를 쓰지 않게)
        BufferedImage source = decode(bytes);
        if (source == null) return invalidImage();

        // 7. 다시 그려서 저장 (원본 바이트를 저장하지 않으므로 숨긴 스크립트·EXIF가 사라짐)
        long version = images.save(userId, resize(source));

        // 8. DB에는 파일명만
        String fileName = ProfileImageStore.fileNameOf(userId);
        jdbc.update("UPDATE users SET picture = ?, updated = LOCALTIMESTAMP WHERE user_id = ?", fileName, userId);

        // 9. 응답
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("profileImageUrl", ProfileImageStore.URL_PATH + fileName + "?v=" + version);
        return ResponseEntity.ok().body(body("PROFILE_IMAGE_UPDATED", "프로필 사진이 변경되었습니다.", result));
    }

    /** 허용이면 0, 초과면 다시 시도까지 남은 초 */
    private long takeAttempt(long userId) {
        long now = System.currentTimeMillis();
        Deque<Long> q = attempts.computeIfAbsent(userId, k -> new ArrayDeque<>());
        synchronized (q) {
            while (!q.isEmpty() && q.peekFirst() <= now - WINDOW_MS) q.pollFirst();
            if (q.size() >= LIMIT_PER_HOUR) return Math.max(1, (q.peekFirst() + WINDOW_MS - now + 999) / 1000);
            q.addLast(now);
            return 0;
        }
    }

    private static boolean isJpeg(byte[] b) {
        return b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF;
    }

    private static boolean isPng(byte[] b) {
        return b.length >= 4 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G';
    }

    /** 열리지 않거나 4,096px 초과면 null */
    private static BufferedImage decode(byte[] bytes) {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (in == null) return null;
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) return null;
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                int w = reader.getWidth(0);
                int h = reader.getHeight(0);
                if (w <= 0 || h <= 0 || w > MAX_DIMENSION || h > MAX_DIMENSION) return null;
                return reader.read(0);
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** 가운데 정사각형을 잘라 400×400으로 */
    private static BufferedImage resize(BufferedImage src) {
        int side = Math.min(src.getWidth(), src.getHeight());
        int x = (src.getWidth() - side) / 2;
        int y = (src.getHeight() - side) / 2;
        BufferedImage out = new BufferedImage(OUT_SIZE, OUT_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(src, 0, 0, OUT_SIZE, OUT_SIZE, x, y, x + side, y + side, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    /** 10MB(멀티파트 한도)를 넘어 본문을 읽지 못한 경우도 413. 토큰을 먼저 본다 */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> tooLarge(HttpServletRequest request) {
        try {
            jwtUtil.getUid(request);
        } catch (JwtAuthException e) {
            return jwtError(e);
        }
        return fileTooLarge();
    }

    /** 깨진 multipart 본문 → 400 INVALID_INPUT. 토큰을 먼저 본다 */
    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<Map<String, Object>> badMultipart(HttpServletRequest request) {
        try {
            jwtUtil.getUid(request);
        } catch (JwtAuthException e) {
            return jwtError(e);
        }
        return invalidInput("multipart/form-data 형식으로 보내주세요.");
    }

    /** 파일 저장 실패 등 → 500 (기존 사진은 임시 파일 방식이라 그대로) */
    @ExceptionHandler(IOException.class)
    public ResponseEntity<Map<String, Object>> ioError(IOException e) {
        log.error("POST /mypage/edit_prof 저장 실패: {}", e.getClass().getName());
        return ResponseEntity.status(500).body(body("INTERNAL_ERROR", "서버 오류가 발생했습니다.", null));
    }

    private static ResponseEntity<Map<String, Object>> fileTooLarge() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("maxBytes", MAX_BYTES);
        return ResponseEntity.status(413).body(body("FILE_TOO_LARGE", "5MB 이하의 이미지만 올릴 수 있습니다.", result));
    }

    private static ResponseEntity<Map<String, Object>> invalidImage() {
        return ResponseEntity.badRequest().body(body("INVALID_IMAGE", "이미지 파일을 읽을 수 없습니다.", null));
    }

    /** image 필드 하나짜리 400 INVALID_INPUT (같은 이름의 공통 헬퍼를 가리므로 ApiResponse.로 부른다) */
    private static ResponseEntity<Map<String, Object>> invalidInput(String reason) {
        List<Map<String, String>> errors = new ArrayList<>();
        errors.add(error("image", reason));
        return ApiResponse.invalidInput(errors);
    }
}
