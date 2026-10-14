package com.example.sesacedu.student.lecture;

import com.example.sesacedu.global.auth.CurrentUser;
import com.example.sesacedu.global.exception.ApiException;
import com.example.sesacedu.student.lecture.EnrollmentProgress;
import com.example.sesacedu.student.lecture.LectureCard;
import com.example.sesacedu.student.lecture.LectureDetail;
import com.example.sesacedu.student.lecture.OtherLecture;
import com.example.sesacedu.student.lecture.ReviewSummary;
import com.example.sesacedu.student.lecture.dto.InstructorPublicResponse;
import com.example.sesacedu.student.lecture.dto.LectureDetailResponse;
import com.example.sesacedu.student.lecture.dto.LectureListItemResponse;
import com.example.sesacedu.student.lecture.dto.LectureListResponse;
import com.example.sesacedu.student.lecture.dto.OtherLectureResponse;
import com.example.sesacedu.student.lecture.dto.PageResponse;
import com.example.sesacedu.student.lecture.LectureRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 강의 조회의 판단을 담당한다.
 *
 * 들어온 값이 쓸 수 있는 값인지 검사하고, 기본값을 채우고, 응답에 무엇을 담을지 정한다.
 * SQL 은 직접 쓰지 않고 Repository 에 맡긴다.
 */
@Service("studentLectureService")
public class LectureService {

    static final int DEFAULT_PAGE = 1;
    static final int DEFAULT_SIZE = 12;
    static final int MAX_SIZE = 50;
    static final String DEFAULT_SORT = "lid_desc";

    private final LectureRepository lectureRepository;
    private final LectureFileStorage storage;

    public LectureService(LectureRepository lectureRepository, LectureFileStorage storage) {
        this.lectureRepository = lectureRepository;
        this.storage = storage;
    }

    public LectureListResponse list(String keyword, String category, String level,
                                    String sort, Integer page, Integer size) {
        // 1단계 — 안 보낸 값에 기본값을 채운다.
        int resolvedPage = page == null ? DEFAULT_PAGE : page;
        int resolvedSize = size == null ? DEFAULT_SIZE : size;
        // 2단계 — 범위를 검사한다. page=0, size=-1, size=99999 를 여기서 끊는다.
        if (resolvedPage < 1 || resolvedSize < 1 || resolvedSize > MAX_SIZE) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PAGE", "페이지 조건이 올바르지 않습니다.");
        }

        // offset = 몇 번째 행부터 건너뛸지. (페이지-1) x 크기 다.
        int offset = (resolvedPage - 1) * resolvedSize;
        // 3단계 — 정렬 문자열을 SQL 의 ORDER BY 로 바꾼다.
        String resolvedSort = (sort == null || sort.isBlank()) ? DEFAULT_SORT : sort.trim();
        String orderBy = toOrderBy(resolvedSort);

        // 공백만 보낸 값은 "필터를 걸지 않음"으로 본다. 그대로 두면 이름이 공백인
        // 강의를 찾는 조건이 되어 결과가 늘 0건이 된다.
        String resolvedKeyword = blankToNull(keyword);
        String resolvedCategory = filterValue(category);
        String resolvedLevel = filterValue(level);

        // 4단계 — 전체 건수를 먼저 센다. 프론트가 페이지 버튼을 몇 개 그릴지 알아야 한다.
        int total = lectureRepository.countOpenLectures(resolvedKeyword, resolvedCategory, resolvedLevel);
        // 0건이면 목록 조회를 생략한다. 어차피 빈 결과라 DB 를 한 번 덜 다녀온다.
        List<LectureCard> rows = total == 0
                ? Collections.emptyList()
                : lectureRepository.findOpenLectures(
                resolvedKeyword, resolvedCategory, resolvedLevel, orderBy, offset, resolvedSize);

        // 5단계 — DB 에서 꺼낸 LectureCard 를 응답용 DTO 로 옮겨 담는다.
        // 조회 결과 객체를 그대로 내보내지 않는 이유는 DTO 클래스 쪽 주석에 적어 뒀다.
        List<LectureListItemResponse> lectures = rows.stream()
                .map(row -> new LectureListItemResponse(
                        row.getLid(),
                        row.getName(),
                        row.getHeadline(),
                        row.getPicture(),
                        row.getInstructor(),
                        row.getCategory(),
                        row.getLevel(),
                        row.getPrice(), row.getRating(), row.getReviewCount()))
                .collect(Collectors.toList());

        // 전체 페이지 수는 올림이다. 25건을 12개씩 보면 3페이지다(2페이지가 아니다).
        // double 로 나누지 않으면 정수 나눗셈이 되어 마지막 페이지가 사라진다.
        int totalPages = total == 0 ? 0 : (int) Math.ceil(total / (double) resolvedSize);
        return new LectureListResponse(
                lectures,
                new PageResponse(resolvedPage, resolvedSize, total, totalPages));
    }

    /**
     * 강의 상세. 보는 사람에 따라 응답이 세 갈래로 갈린다.
     *
     *   guest     토큰이 없다               가격을 보여 주고 구매를 안내한다
     *   member    토큰은 검증됐지만 안 샀다  같다
     *   enrolled  enrollments 에 행이 있다   가격 대신 이어보기·자료·과제 주소를 준다
     */
    public LectureDetailResponse detail(int lid, CurrentUser currentUser) {
        LectureDetail lecture = lectureRepository.findOpenById(lid)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND, "LECTURE_NOT_FOUND", "강의를 찾을 수 없습니다."));

        ReviewSummary reviews = lectureRepository.findReviewSummary(lid);
        List<OtherLecture> others = lectureRepository.findOtherOpenLectures(
                lecture.getInstructorUserId(), lid);

        LectureDetailResponse.Builder builder = new LectureDetailResponse.Builder()
                .lectureId(lecture.getLid())
                .name(lecture.getName())
                .headline(lecture.getHeadline())
                .summary(lecture.getSummary())
                .category(lecture.getCategory())
                .level(lecture.getLevel())
                .picture(lecture.getPicture())
                // 맛보기 영상은 재생 API 주소로 준다.
                .preview(lecture.getPreview() == null ? null : "/lectures/" + lid + "/preview")
                .price(lecture.getPrice())
                .intro(toIntro(lecture.getSummary()))
                .instructor(new InstructorPublicResponse(
                        lecture.getInstructorUserId(),
                        lecture.getInstructorName(),
                        null,
                        lecture.getInstructorBio(),
                        lecture.getInstructorField(),
                        lecture.getInstructorCareer()))
                .otherLectures(others.stream()
                        .map(item -> new OtherLectureResponse(item.getLid(), item.getName(), item.getPrice()))
                        .collect(Collectors.toList()))
                .rating(reviews.getRating())
                .reviewCount(reviews.getReviewCount());

        // 토큰이 없으면 guest 다. 여기서 끝낸다.
        if (currentUser == null) {
            return builder.viewerState("guest").action("purchase").build();
        }

        // 로그인한 사용자다. 이 강의를 수강 중인지 enrollments 로 확인한다.
        return lectureRepository.findEnrollment(currentUser.getUserId(), lid)
                .map(progress -> enrolled(builder, lid, progress))
                .orElseGet(() -> builder.viewerState("member").action("purchase").build());
    }

    /** GET /lectures/{Lid}/preview — 맛보기 영상 파일. 로그인하지 않아도 받는다. */
    public StoredFile previewFile(int lid) {
        LectureDetail lecture = lectureRepository.findOpenById(lid)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND, "LECTURE_NOT_FOUND", "강의를 찾을 수 없습니다."));

        Path path = storage.resolve(lecture.getPreview(),
                "PREVIEW_NOT_FOUND", "등록된 맛보기 영상이 없습니다.");
        return new StoredFile(path, lecture.getName());
    }

    private LectureDetailResponse enrolled(LectureDetailResponse.Builder builder,
                                           int lid,
                                           EnrollmentProgress progress) {
        return builder
                .viewerState("enrolled")
                .action("continue")
                // 이미 산 사람에게는 가격을 보여 주지 않는다.
                .price(null)
                .done(progress.isDone())
                .position(progress.getPosition())
                // 수강 화면 · 자료 · 과제 API 주소.
                .learnUrl("/lectures/" + lid + "/learn")
                .materialUrl("/lectures/" + lid + "/material")
                .assignmentUrl("/lectures/" + lid + "/assignment")
                .build();
    }

    /** 화면의 정렬 값(기본순 · 낮은 가격순 · 높은 가격순)을 ORDER BY 로 바꾼다. */
    private String toOrderBy(String sort) {
        switch (sort) {
            case "lid_desc":
            case "id_desc":  // 노션 기본값 표기의 별칭
            case "default":  // 프론트 /courses 의 "기본순"
                return "l.Lid DESC";
            case "latest": // 기존 코드 호출 호환
                return "l.created DESC NULLS LAST, l.Lid DESC";
            case "price_asc":
            case "low":      // 프론트 /courses 의 "낮은 가격순"
                return "l.Lprice ASC, l.Lid DESC";
            case "price_desc":
            case "pric_desc": // 노션에 기재된 오탈자도 허용
            case "high":      // 프론트 /courses 의 "높은 가격순"
                return "l.Lprice DESC, l.Lid DESC";
            default:
                return sort;
        }
    }

    /**
     * 분류·난이도 필터 값을 정리한다.
     *
     * 화면의 "전체" 선택지가 `all` 이라는 값을 보낸다. 그대로 조건에 넣으면
     * 난이도가 "all" 인 강의를 찾게 되어 결과가 늘 0건이 된다. 값이 아니라
     * "필터 없음" 이라는 신호이므로 null 로 바꾼다.
     *
     * 검색어(keyword)에는 적용하지 않는다. "all" 이라는 단어를 검색하려는 사람이
     * 있을 수 있고, 그건 실제 검색어다.
     */
    private String filterValue(String value) {
        String trimmed = blankToNull(value);
        return "all".equalsIgnoreCase(trimmed) ? null : trimmed;
    }

    private String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    // summary 를 줄바꿈으로 쪼개 소개 문단 배열로 만든다.
    // 소개 항목을 따로 저장하는 컬럼이 아직 없어서 임시로 이렇게 나눈다.
    private List<String> toIntro(String summary) {
        if (summary == null || summary.isBlank()) {
            return Collections.emptyList();
        }
        return Arrays.stream(summary.split("\\r?\\n"))
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .collect(Collectors.toList());
    }
}
