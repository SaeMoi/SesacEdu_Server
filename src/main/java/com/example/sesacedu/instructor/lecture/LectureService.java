package com.example.sesacedu.instructor.lecture;

import com.example.sesacedu.global.storage.FileStorage;
import com.example.sesacedu.global.storage.LectureThumbnail;
import com.example.sesacedu.instructor.lecture.dto.LectureDetail;
import com.example.sesacedu.instructor.lecture.dto.LectureListItem;
import com.example.sesacedu.instructor.lecture.dto.LectureSaveRequest;
import com.example.sesacedu.instructor.lecture.dto.PageResponse;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Locale;

/*
 * ===================== [취약 진단용 - lecture] =====================
 *  (V-AUTHZ/A01)  소유권 검사 제거 : update/uploadFiles/deleteFile/updateAssignment 의 isOwnedByInstructor 삭제
 *  (V-UPLOAD/A05) 업로드 확장자 검사 제거 : 썸네일/영상/자료 형식 검사 삭제 → 임의 파일 업로드(웹셸·스크립트 등)
 * ================================================================
 */
@Service
public class LectureService {

    private static final int PAGE_SIZE = 5;

    private final LectureRepository lectureRepository;
    private final FileStorage fileStorage;

    public LectureService(LectureRepository lectureRepository, FileStorage fileStorage) {
        this.lectureRepository = lectureRepository;
        this.fileStorage = fileStorage;
    }

    public PageResponse<LectureListItem> getList(long instructorUserId, String status, int page) {
        int safePage = Math.max(page, 1);
        String safeStatus = (status == null || status.isBlank()) ? null : status.trim();

        long total = lectureRepository.countList(instructorUserId, safeStatus);
        int offset = (safePage - 1) * PAGE_SIZE;

        List<LectureListItem> content =
                lectureRepository.findList(instructorUserId, safeStatus, offset, PAGE_SIZE);

        return new PageResponse<>(content, safePage, PAGE_SIZE, total);
    }

    /** 상세. [V-AUTHZ] 소유권 검증 없음(IDOR). */
    public LectureDetail getDetail(int lid, long instructorUserId) {
        LectureDetail d = lectureRepository.findDetail(lid, instructorUserId);
        if (d == null) {
            throw new NotFoundOrForbidden();
        }
        return d;
    }

    /** 등록. [V-UPLOAD] 썸네일 확장자 검사 없이 저장. */
    public int create(long instructorUserId, LectureSaveRequest req, MultipartFile thumbnail) {
        String status = req.getStatus() == null ? "draft" : req.getStatus().trim().toLowerCase(Locale.ROOT);
        int lid = lectureRepository.insert(instructorUserId, req, status);
        if (thumbnail != null && !thumbnail.isEmpty()) storeThumbnail(lid, thumbnail);
        return lid;
    }

    public boolean isInstructor(long userId) {
        return lectureRepository.isInstructor(userId);
    }

    /** 수정. [V-AUTHZ] 소유권 검사 삭제. [V-UPLOAD] 썸네일 검사 없음. */
    public void update(int lid, long instructorUserId, LectureSaveRequest req, MultipartFile thumbnail) {
        String status = req.getStatus() == null ? "draft" : req.getStatus().trim().toLowerCase(Locale.ROOT);
        lectureRepository.updateBasic(lid, req, status);
        if (thumbnail != null && !thumbnail.isEmpty()) {
            storeThumbnail(lid, thumbnail);
        }
    }

    /** 자료 업로드. [V-AUTHZ] 소유권 검사 삭제. [V-UPLOAD] 확장자 검사 삭제 → 아무 파일이나 업로드. */
    public void uploadFiles(int lid, long instructorUserId, MultipartFile video, MultipartFile material) {
        if (video != null && !video.isEmpty()) {
            String path = fileStorage.store(video, "lecture/video");
            lectureRepository.updateVideo(lid, path, video.getOriginalFilename());
        }
        if (material != null && !material.isEmpty()) {
            String path = fileStorage.store(material, "lecture/material");
            lectureRepository.updateMaterial(lid, path, material.getOriginalFilename());
        }
    }

    /** 자료 삭제. [V-AUTHZ] 소유권 검사 삭제. */
    public void deleteFile(int lid, long instructorUserId, String slot) {
        String path = lectureRepository.findFilePath(lid, slot);
        if ("video".equals(slot)) {
            lectureRepository.updateVideo(lid, null, null);
        } else {
            lectureRepository.updateMaterial(lid, null, null);
        }
        fileStorage.delete(path);
    }

    /** 과제 설정. [V-AUTHZ] 소유권 검사 삭제. [V-VALID] 길이 검사 삭제. */
    public void updateAssignment(int lid, long instructorUserId, String title, String content, String fileType) {
        String t = title == null || title.isBlank() ? null : title;
        String f = fileType == null || fileType.isBlank() ? null : fileType;
        String c = content == null || content.isBlank() ? null : content;
        lectureRepository.updateAssignment(lid, t, t == null ? null : c, t == null ? null : f);
    }

    // ---------------- 헬퍼 ----------------

    /** [V-UPLOAD] 확장자 검사 없이 저장. */
    private void storeThumbnail(int lid, MultipartFile thumbnail) {
        String path = fileStorage.store(thumbnail, LectureThumbnail.STORAGE_PREFIX);
        String fileName = path.substring(path.lastIndexOf('/') + 1);
        lectureRepository.updatePicture(lid, LectureThumbnail.URL_PATH + fileName);
    }

    /** 강의가 없거나 남의 강의 (404). */
    public static class NotFoundOrForbidden extends RuntimeException {
    }

    /** 지원하지 않는 파일 확장자 (400) — [V-UPLOAD] 더 이상 던지지 않음(호환용 유지). */
    public static class UnsupportedFileType extends RuntimeException {
        public UnsupportedFileType(String message) {
            super(message);
        }
    }
}