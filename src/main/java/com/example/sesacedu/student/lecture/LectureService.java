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
@Service
public class LectureService {

    static final int DEFAULT_PAGE = 1;
    static final int DEFAULT_SIZE = 12;
    // 한 번에 가져갈 수 있는 최대 개수. 상한을 두지 않으면 size=1000000 한 번으로
    // DB 와 메모리를 모두 끌어다 쓰게 된다.
    static final int MAX_SIZE = 50;
    static final String DEFAULT_SORT = "lid_desc";

    private final LectureRepository lectureRepository;

    public LectureService(LectureRepository lectureRepository) {
        this.lectureRepository = lectureRepository;
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
        // int 로 계산하면 page 가 아주 큰 값일 때 넘쳐서 음수가 되고, 그러면 범위 검사를
        // 통과해 버린다. 그래서 long 으로 계산한 뒤 int 한계를 넘는지 본다.
        long requestedOffset = (long) (resolvedPage - 1) * resolvedSize;
        if (requestedOffset > Integer.MAX_VALUE) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PAGE", "페이지 조건이 올바르지 않습니다.");
        }
        // 3단계 — 정렬 문자열을 실제 SQL 조각으로 바꾼다. 아래 toOrderBy 의 주석을 볼 것.
        String resolvedSort = (sort == null || sort.isBlank()) ? DEFAULT_SORT : sort.trim();
        String orderBy = toOrderBy(resolvedSort);

        // 공백만 보낸 값은 "필터를 걸지 않음"으로 본다. 그대로 두면 이름이 공백인
        // 강의를 찾는 조건이 되어 결과가 늘 0건이 된다.
        String resolvedKeyword = blankToNull(keyword);
        String resolvedCategory = blankToNull(category);
        String resolvedLevel = blankToNull(level);

        // 4단계 — 전체 건수를 먼저 센다. 프론트가 페이지 버튼을 몇 개 그릴지 알아야 한다.
        int total = lectureRepository.countOpenLectures(resolvedKeyword, resolvedCategory, resolvedLevel);
        int offset = (int) requestedOffset;
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
     *
     * 수강 여부를 users.auth 로 보지 않는 것이 중요하다. auth 는 계정 전체에 하나뿐인
     * 값이라 auth=1(수강생)로 판정하면 사지 않은 강의까지 열린다. 어떤 강의를 볼 권한이
     * 있는지는 (user_id, Lid) 한 쌍으로만 알 수 있고, 그 쌍이 enrollments 다.
     */
    public LectureDetailResponse detail(int lid, CurrentUser currentUser) {
        // 0 이나 음수는 DB 에 물어볼 필요도 없다.
        if (lid < 1) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "강의 번호가 올바르지 않습니다.");
        }
        // 없는 강의와 비공개(draft·closed) 강의를 똑같이 404 로 답한다.
        // "있지만 비공개"와 "없음"을 다르게 답하면 번호를 하나씩 넣어 보는 것만으로
        // 아직 공개하지 않은 강의가 몇 번에 있는지 알아낼 수 있다.
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
                .preview(lecture.getPreview())
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

        // 토큰이 검증된 사용자다. 그 사람이 이 강의를 수강 중인지 enrollments 로 확인한다.
        // currentUser.getUserId() 는 검증된 토큰에서 나온 값이라 프론트가 바꿀 수 없다.
        return lectureRepository.findEnrollment(currentUser.getUserId(), lid)
                .map(progress -> enrolled(builder, lid, progress))
                .orElseGet(() -> builder.viewerState("member").action("purchase").build());
    }

    private LectureDetailResponse enrolled(LectureDetailResponse.Builder builder,
                                           int lid,
                                           EnrollmentProgress progress) {
        return builder
                .viewerState("enrolled")
                .action("continue")
                // 이미 산 사람에게는 가격을 내려보내지 않는다. 화면에 쓰지 않는 값이고,
                // 응답에 안 넣을 수 있는 필드는 안 넣는 쪽이 맞다.
                .price(null)
                .done(progress.isDone())
                .position(progress.getPosition())
                // 영상·자료의 실제 저장 경로가 아니라 "다음에 호출할 API 주소"다.
                // 실제 경로를 여기 넣으면 그 주소를 받은 사람이 권한 검사를 건너뛰고
                // 파일을 직접 가져갈 수 있다. 권한은 저 API 들이 각각 다시 확인한다.
                .learnUrl("/lectures/" + lid + "/learn")
                .materialUrl("/lectures/" + lid + "/material")
                .assignmentUrl("/lectures/" + lid + "/assignment")
                .build();
    }

    /**
     * 정렬 화이트리스트. 이 코드에서 보안상 가장 중요한 부분이다.
     *
     * SQL 의 값 자리(WHERE name = ?)는 ? 로 바인딩할 수 있지만 ORDER BY 자리는 안 된다.
     * 값이 아니라 문법의 일부이기 때문이다. ORDER BY ? 라고 쓰면 동작하지 않는다.
     *
     * 그래서 들어온 문자열을 SQL 에 그대로 이어 붙이면 그 자리가 SQL 인젝션 통로가 된다.
     * 대신 허용할 값을 미리 정해 두고, 목록에 있는 것만 정해진 SQL 조각으로 바꾼다.
     * 목록에 없으면 400 으로 거절한다. 사용자 입력이 SQL 에 닿지 않는다.
     */
    private String toOrderBy(String sort) {
        switch (sort) {
            case "lid_desc":
            case "id_desc": // 노션 기본값 표기의 별칭
                return "l.Lid DESC";
            case "latest": // 기존 코드 호출 호환
                return "l.created DESC NULLS LAST, l.Lid DESC";
            case "price_asc":
                return "l.Lprice ASC, l.Lid DESC";
            case "price_desc":
            case "pric_desc": // 노션에 기재된 오탈자도 허용
                return "l.Lprice DESC, l.Lid DESC";
            // 목록에 없는 값은 전부 여기로 떨어진다. 이게 화이트리스트의 핵심이다.
            default:
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_SORT", "정렬 조건이 올바르지 않습니다.");
        }
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
