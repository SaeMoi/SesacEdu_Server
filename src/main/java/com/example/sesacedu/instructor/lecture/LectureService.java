package com.example.sesacedu.instructor.lecture;

import com.example.sesacedu.global.storage.FileStorage;
import com.example.sesacedu.instructor.lecture.dto.LectureDetail;
import com.example.sesacedu.instructor.lecture.dto.LectureListItem;
import com.example.sesacedu.instructor.lecture.dto.LectureSaveRequest;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 강의 관리 로직.
 *   - 목록 / 상세 조회
 *   - 등록(기본정보 + 썸네일 → draft) / 수정(기본정보 + 썸네일)
 *   - 자료 업로드 (영상·자료 슬롯 2개)
 * 공개 가드: status=open 은 영상·자료가 모두 있어야 통과.
 * 권한: 본인 강의만 조회·수정 가능. instructorUserId 는 컨트롤러가 JWT 에서 추출해 넘긴다.
 */
@Service
public class LectureService {

    private static final String STATUS_DRAFT = "draft";
    private static final String STATUS_OPEN = "open";
    private static final String STATUS_CLOSED = "closed";

    private static final Set<String> IMAGE_EXT =
            Set.of("png", "jpg", "jpeg", "svg", "gif", "webp");

    private final LectureRepository lectureRepository;
    private final FileStorage fileStorage;

    public LectureService(LectureRepository lectureRepository, FileStorage fileStorage) {
        this.lectureRepository = lectureRepository;
        this.fileStorage = fileStorage;
    }

    /** 목록 */
    public List<LectureListItem> getList(long instructorUserId, String status) {
        return lectureRepository.findList(instructorUserId, status);
    }

    /** 상세. 없거나 남의 강의면 예외. */
    public LectureDetail getDetail(int lid, long instructorUserId) {
        LectureDetail d = lectureRepository.findDetail(lid, instructorUserId);
        if (d == null) {
            throw new NotFoundOrForbidden();
        }
        return d;
    }

    /**
     * 강의 등록. 기본정보 + 썸네일(선택)을 저장한다("저장하고 다음").
     * status 를 안 주면 draft. 새 강의는 영상·자료가 아직 없으므로 open 은 공개 가드에 걸린다.
     * @return 생성된 lid
     */
    public int create(long instructorUserId, LectureSaveRequest req, MultipartFile thumbnail) {
        String status = normalizeStatus(req.getStatus(), STATUS_DRAFT);
        if (STATUS_OPEN.equals(status)) {
            throw new CannotPublish();
        }
        int lid = lectureRepository.insert(instructorUserId, req, status);
        if (thumbnail != null && !thumbnail.isEmpty()) {
            storeThumbnail(lid, thumbnail);
        }
        return lid;
    }

    /**
     * 강의 수정. 기본정보 + 썸네일(선택). 본인 강의만.
     * status 생략 시 기존 상태 유지. status=open 은 영상·자료가 DB에 모두 있어야 통과.
     */
    public void update(int lid, long instructorUserId, LectureSaveRequest req, MultipartFile thumbnail) {
        if (!lectureRepository.isOwnedByInstructor(lid, instructorUserId)) {
            throw new NotFoundOrForbidden();
        }
        String status = req.getStatus();
        if (status == null || status.isBlank()) {
            LectureDetail cur = lectureRepository.findDetail(lid, instructorUserId);
            status = cur != null ? cur.getStatus() : STATUS_DRAFT;
        } else {
            status = normalizeStatus(status, STATUS_DRAFT);
        }
        if (STATUS_OPEN.equals(status) && !lectureRepository.hasVideoAndMaterial(lid)) {
            throw new CannotPublish();
        }
        lectureRepository.updateBasic(lid, req, status);
        if (thumbnail != null && !thumbnail.isEmpty()) {
            storeThumbnail(lid, thumbnail);
        }
    }

    /**
     * 자료 업로드. 영상(mp4)·자료(pdf/zip)를 슬롯별로 받는다.
     *   - 둘 다 비어 있으면 400 (올릴 게 없음).
     *   - 보낸 칸만 교체, 안 보낸 칸은 그대로 둔다.
     * 본인 강의만. 슬롯별 허용 확장자가 아니면 예외.
     */
    public void uploadFiles(int lid, long instructorUserId, MultipartFile video, MultipartFile material) {
        if (!lectureRepository.isOwnedByInstructor(lid, instructorUserId)) {
            throw new NotFoundOrForbidden();
        }
        boolean hasVideo = video != null && !video.isEmpty();
        boolean hasMaterial = material != null && !material.isEmpty();
        if (!hasVideo && !hasMaterial) {
            throw new IllegalArgumentException("업로드할 파일이 없습니다.");
        }

        if (hasVideo) {
            String ext = extensionOf(video.getOriginalFilename());
            if (!ext.equals("mp4")) {
                throw new UnsupportedFileType("영상은 mp4 만 가능합니다: " + ext);
            }
            String path = fileStorage.store(video, "lecture/video");
            lectureRepository.updateVideo(lid, path);
        }
        if (hasMaterial) {
            String ext = extensionOf(material.getOriginalFilename());
            if (!ext.equals("pdf") && !ext.equals("zip")) {
                throw new UnsupportedFileType("자료는 pdf/zip 만 가능합니다: " + ext);
            }
            String path = fileStorage.store(material, "lecture/material");
            lectureRepository.updateMaterial(lid, path);
        }
    }

    // ---------------- 헬퍼 ----------------

    private void storeThumbnail(int lid, MultipartFile thumbnail) {
        String ext = extensionOf(thumbnail.getOriginalFilename());
        if (!IMAGE_EXT.contains(ext)) {
            throw new UnsupportedFileType("썸네일은 이미지(png/jpg/jpeg/svg/gif/webp)만 가능합니다: " + ext);
        }
        String path = fileStorage.store(thumbnail, "lecture/picture");
        lectureRepository.updatePicture(lid, path);
    }

    private String normalizeStatus(String status, String fallback) {
        if (status == null) return fallback;
        String s = status.trim().toLowerCase(Locale.ROOT);
        if (s.equals(STATUS_DRAFT) || s.equals(STATUS_OPEN) || s.equals(STATUS_CLOSED)) {
            return s;
        }
        return fallback;
    }

    private String extensionOf(String filename) {
        if (filename == null) return "";
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) return "";
        return filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** 강의가 없거나 남의 강의 (404). */
    public static class NotFoundOrForbidden extends RuntimeException {
    }

    /** 지원하지 않는 파일 확장자 (400). */
    public static class UnsupportedFileType extends RuntimeException {
        public UnsupportedFileType(String message) {
            super(message);
        }
    }

    /** 공개 조건(영상·자료) 미충족 (400). */
    public static class CannotPublish extends RuntimeException {
        public CannotPublish() {
            super("영상과 자료를 모두 업로드해야 공개할 수 있습니다.");
        }
    }
}
