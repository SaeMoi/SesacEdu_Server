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

    /** 강의 목록 페이지 크기 (한 페이지 5개) */
    private static final int PAGE_SIZE = 5;

    private static final Set<String> IMAGE_EXT =
            Set.of("png", "jpg", "jpeg", "svg", "gif", "webp");

    private final LectureRepository lectureRepository;
    private final FileStorage fileStorage;

    public LectureService(LectureRepository lectureRepository, FileStorage fileStorage) {
        this.lectureRepository = lectureRepository;
        this.fileStorage = fileStorage;
    }

    /**
     * 목록 (페이지네이션, 한 페이지 {@value #PAGE_SIZE}개).
     * status 가 비어 있으면 전체. page 는 1-base 이며 1 미만이면 1로 보정한다.
     */
    public PageResponse<LectureListItem> getList(long instructorUserId, String status, int page) {
        int safePage = Math.max(page, 1);
        String safeStatus = (status == null || status.isBlank()) ? null : status.trim();

        long total = lectureRepository.countList(instructorUserId, safeStatus);
        int offset = (safePage - 1) * PAGE_SIZE;

        List<LectureListItem> content =
                lectureRepository.findList(instructorUserId, safeStatus, offset, PAGE_SIZE);

        return new PageResponse<>(content, safePage, PAGE_SIZE, total);
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
     * 강의 등록. 기본정보 + 썸네일을 저장한다.
     * status(open/closed/draft)는 필수이며 컨트롤러에서 검증된 값이 들어온다(요청 그대로 저장).
     * 썸네일은 선택. 안 보내면 picture 는 NULL 이고 조회 시 서버 기본 썸네일 주소가 나간다.
     * @return 생성된 lid
     */
    public int create(long instructorUserId, LectureSaveRequest req, MultipartFile thumbnail) {
        // status 는 컨트롤러에서 open/closed/draft 로 검증됨. draft 로 떨어뜨리지 않고 요청 값 그대로 저장한다.
        String status = req.getStatus().trim().toLowerCase(Locale.ROOT);
        // 확장자 검사를 먼저 해서, 형식이 틀리면 강의 행을 만들지 않는다.
        boolean hasThumbnail = thumbnail != null && !thumbnail.isEmpty();
        if (hasThumbnail) checkThumbnailType(thumbnail);
        int lid = lectureRepository.insert(instructorUserId, req, status);
        if (hasThumbnail) storeThumbnail(lid, thumbnail);
        return lid;
    }

    /** 강사 정보(instructors 행)가 있는 계정인지. */
    public boolean isInstructor(long userId) {
        return lectureRepository.isInstructor(userId);
    }

    /**
     * 강의 수정. 기본정보 + 썸네일(선택). 본인 강의만.
     * status(open/closed/draft)는 필수이며 컨트롤러에서 검증된 값이 들어온다(요청 그대로 저장).
     * 썸네일을 보내면 교체, 안 보내면 기존 유지.
     */
    public void update(int lid, long instructorUserId, LectureSaveRequest req, MultipartFile thumbnail) {
        if (!lectureRepository.isOwnedByInstructor(lid, instructorUserId)) {
            throw new NotFoundOrForbidden();
        }
        String status = req.getStatus().trim().toLowerCase(Locale.ROOT);
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
            lectureRepository.updateVideo(lid, path, video.getOriginalFilename());
        }
        if (hasMaterial) {
            String ext = extensionOf(material.getOriginalFilename());
            if (!ext.equals("pdf") && !ext.equals("zip")) {
                throw new UnsupportedFileType("자료는 pdf/zip 만 가능합니다: " + ext);
            }
            String path = fileStorage.store(material, "lecture/material");
            lectureRepository.updateMaterial(lid, path, material.getOriginalFilename());
        }
    }

    /**
     * 자료 삭제. slot 은 video 또는 material. DB 칸을 비우고 저장된 파일도 지운다. 본인 강의만.
     */
    public void deleteFile(int lid, long instructorUserId, String slot) {
        if (!lectureRepository.isOwnedByInstructor(lid, instructorUserId)) {
            throw new NotFoundOrForbidden();
        }
        if (!"video".equals(slot) && !"material".equals(slot)) {
            throw new IllegalArgumentException("삭제할 파일 종류는 video 또는 material 이어야 합니다.");
        }
        String path = lectureRepository.findFilePath(lid, slot);
        if ("video".equals(slot)) {
            lectureRepository.updateVideo(lid, null, null);
        } else {
            lectureRepository.updateMaterial(lid, null, null);
        }
        fileStorage.delete(path);
    }

    /** 과제 설정. 본인 강의만. title 이 비면 과제를 없앤다. */
    public void updateAssignment(int lid, long instructorUserId, String title, String content, String fileType) {
        if (!lectureRepository.isOwnedByInstructor(lid, instructorUserId)) {
            throw new NotFoundOrForbidden();
        }
        String t = title == null || title.isBlank() ? null : title.trim();
        if (t != null && t.length() > 200) throw new IllegalArgumentException("과제 제목은 200자까지입니다.");
        String f = fileType == null || fileType.isBlank() ? null : fileType.trim();
        if (f != null && f.length() > 100) throw new IllegalArgumentException("제출 파일 형식 안내는 100자까지입니다.");
        String c = content == null || content.isBlank() ? null : content;
        lectureRepository.updateAssignment(lid, t, t == null ? null : c, t == null ? null : f);
    }

    // ---------------- 헬퍼 ----------------

    private void checkThumbnailType(MultipartFile thumbnail) {
        String ext = extensionOf(thumbnail.getOriginalFilename());
        if (!IMAGE_EXT.contains(ext)) {
            throw new UnsupportedFileType("썸네일은 이미지(png/jpg/jpeg/svg/gif/webp)만 가능합니다: " + ext);
        }
    }

    /** {저장소}/lecture/picture/ 에 저장하고 DB 에는 공개 주소 /images/lecture/{파일명} 을 넣는다. */
    private void storeThumbnail(int lid, MultipartFile thumbnail) {
        checkThumbnailType(thumbnail);
        String path = fileStorage.store(thumbnail, LectureThumbnail.STORAGE_PREFIX);
        String fileName = path.substring(path.lastIndexOf('/') + 1);
        lectureRepository.updatePicture(lid, LectureThumbnail.URL_PATH + fileName);
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
}