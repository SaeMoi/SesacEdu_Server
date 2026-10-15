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
    /** 이 형식의 파일만 /images/profile/로 보여준다 (임시 파일 등은 404) */
    public static final Pattern FILE_NAME = Pattern.compile("^[0-9]+_profile\\.png$");

    private final Path dir;

    public ProfileImageStore(@Value("${app.upload.profile-dir:./images/profile}") String dir) {
        this.dir = Paths.get(dir).toAbsolutePath().normalize();
    }

    public Path getDir() { return dir; }

    public static String fileNameOf(long userId) { return userId + "_profile.png"; }

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
