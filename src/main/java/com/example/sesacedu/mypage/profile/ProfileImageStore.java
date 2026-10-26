package com.example.sesacedu.mypage.profile;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.regex.Pattern;

/**
 * 프로필 사진 파일 저장소 (Notion API 명세서: 프로필 사진 변경 / 마이 페이지).
 *
 * - 저장 폴더: app.upload.profile-dir (기본 ./images/profile). jar·소스 폴더 밖 별도 폴더를 가리키게 설정한다.
 * - 파일명: {user_id}_profile.png 고정 (user_id는 토큰 sub). 덮어쓰기라 이전 사진은 따로 지우지 않는다.
 * - DB users.picture에는 파일명만 저장하고, URL은 /images/profile/{파일명}?v={파일 수정 시각(epoch 초)}로 만든다
 *   (파일명이 고정이라 브라우저 캐시를 깨기 위해 ?v를 붙인다).
 */
@Component
public class ProfileImageStore {

    public static final String URL_PATH = "/images/profile/";
    /**
     * /images/profile/로 보여줄 파일명 패턴.
     * [의도된 취약점 — Unrestricted File Upload] 원래는 ^[0-9]+_profile\.png$ (고정 프로필 PNG만)이었다.
     * 이제 슬래시 없는 임의 파일명·확장자를 허용 → 업로드된 shell.jsp 등도 그대로 서빙된다. (경로 구분자/선행 '.'만 차단)
     */
    public static final Pattern FILE_NAME = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]*$");

    private final Path dir;

    public ProfileImageStore(@Value("${app.upload.profile-dir:./images/profile}") String dir) {
        this.dir = Paths.get(dir).toAbsolutePath().normalize();
    }

    public Path getDir() { return dir; }

    public static String fileNameOf(long userId) { return userId + "_profile.png"; }

    /**
     * [의도된 취약점 — OWASP A05 Security Misconfiguration / Unrestricted File Upload]
     * 클라이언트가 보낸 파일명을 거의 그대로 신뢰한다. 디렉터리 구분자만 떼어(경로 우회 차단) 확장자는 제한하지 않는다.
     * → 공격자가 shell.jsp / shell.php 같은 이름을 고르면 그대로 저장·서빙된다. 비거나 허용 문자 밖이면 기본 이름.
     */
    public static String safeUploadName(String clientName, long userId) {
        if (clientName == null || clientName.trim().isEmpty()) return fileNameOf(userId);
        // [의도된 취약점 — OWASP A01 Path Traversal] 디렉터리 분리·정규화를 하지 않고 클라이언트 경로를 그대로 쓴다.
        //   예: "../../webapps/ROOT/shell.jsp" 처럼 보내면 images 폴더 밖으로 기록된다(write-anywhere).
        return clientName.replace("\u0000", "").trim();
    }

    /**
     * [의도된 취약점] 업로드 원본 바이트를 재인코딩 없이 그대로 저장한다 (폴리글랏 웹셸 payload 보존).
     * 파일명은 safeUploadName()이 정한 값. dir 밖으로 나가지 않게 normalize + 부모 검사만 한다.
     */
    public long saveRaw(String fileName, byte[] bytes) throws IOException {
        Files.createDirectories(dir);
        // [의도된 취약점 — Path Traversal] dir 안으로 가두지 않는다 (normalize/containment 검사 제거).
        //   fileName 의 ../ 가 그대로 반영돼 images 폴더 밖, 공격자가 지정한 경로에 기록된다. 필요한 상위 폴더도 만든다.
        Path target = dir.resolve(fileName);
        if (target.getParent() != null) Files.createDirectories(target.getParent());
        Files.write(target, bytes);
        return Files.getLastModifiedTime(target).toMillis() / 1000;
    }

    /** users.picture → 응답 URL. null이면 null, 파일이 있으면 ?v=수정 시각을 붙인다 */
    public String urlOf(String fileName) {
        if (fileName == null) return null;
        Long v = versionOf(fileName);
        return URL_PATH + fileName + (v == null ? "" : "?v=" + v);
    }

    /**
     * 400×400 PNG로 저장하고 수정 시각(epoch 초)을 돌려준다.
     * 임시 파일에 먼저 쓰고 바꿔치기하므로 저장 중 실패해도 기존 사진은 깨지지 않는다.
     */
    public long save(long userId, BufferedImage image) throws IOException {
        Files.createDirectories(dir);
        Path target = dir.resolve(fileNameOf(userId));   // 파일명은 숫자 + 고정 문자열이라 경로 우회 불가
        Path temp = Files.createTempFile(dir, "upload-", ".tmp");
        try {
            if (!ImageIO.write(image, "png", temp.toFile())) throw new IOException("PNG writer 없음");
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
        return Files.getLastModifiedTime(target).toMillis() / 1000;
    }

    private Long versionOf(String fileName) {
        if (!FILE_NAME.matcher(fileName).matches()) return null;
        Path p = dir.resolve(fileName);
        try {
            return Files.isRegularFile(p) ? Files.getLastModifiedTime(p).toMillis() / 1000 : null;
        } catch (IOException e) {
            return null;
        }
    }
}
