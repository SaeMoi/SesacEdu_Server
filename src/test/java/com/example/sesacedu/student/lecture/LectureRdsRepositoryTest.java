package com.example.sesacedu.student.lecture;

import com.example.sesacedu.student.lecture.dto.ChapterResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 강의 SQL 을 공유 RDS(.env 의 DB_URL)에서 실제로 실행한다.
 *
 * 기준 데이터: 강의 40개(1~35 open, 36~38 closed, 39~40 draft), 강의마다 목차 5개,
 * user 31 은 강의 1 수강·리뷰·제출물(aid 1) 보유, user 41 은 강의 2 를 수강하고 리뷰·제출물이 없다.
 *
 * 쓰기는 클래스 전체가 @Transactional 이라 테스트가 끝나면 롤백된다. RDS 데이터는 바뀌지 않는다.
 */
@EnabledIfEnvironmentVariable(named = "RUN_DB_TESTS", matches = "true")
@SpringBootTest
@Transactional
class LectureRdsRepositoryTest {

    @Autowired LectureRepository lectures;
    @Autowired LectureClassroomRepository classroom;
    @Autowired LectureBoardRepository board;

    // ───────── 읽기 ─────────

    @Test
    void 목록은_공개_강의만_세고_강의번호_내림차순으로_준다() {
        assertThat(lectures.countOpenLectures(null, null, null)).isEqualTo(35);

        List<LectureCard> page = lectures.findOpenLectures(null, null, null, "l.Lid DESC", 0, 12);
        assertThat(page).hasSize(12);
        assertThat(page.get(0).getLid()).isEqualTo(35);
    }

    @Test
    void 분류와_검색어로_거른다() {
        List<LectureCard> web = lectures.findOpenLectures(null, "웹 보안", null, "l.Lid DESC", 0, 50);
        assertThat(web).isNotEmpty().allMatch(c -> "웹 보안".equals(c.getCategory()));

        assertThat(lectures.countOpenLectures("파이썬", null, null)).isPositive();
    }

    @Test
    void 상세는_공개_강의만_나온다() {
        assertThat(lectures.findOpenById(1)).isPresent();
        assertThat(lectures.findOpenById(36)).isEmpty();
        assertThat(lectures.findOpenById(39)).isEmpty();
    }

    @Test
    void 수강_화면은_공개와_내려간_강의만_열린다() {
        assertThat(classroom.findForEnrolled(1)).isPresent();
        assertThat(classroom.findForEnrolled(36)).isPresent();
        assertThat(classroom.findForEnrolled(39)).isEmpty();
    }

    @Test
    void 목차는_시작_초_순서로_5개다() {
        List<ChapterResponse> chapters = classroom.findChapters(1);
        assertThat(chapters).hasSize(5);
        assertThat(chapters.get(0).getStartSec()).isZero();
        for (int i = 1; i < chapters.size(); i++) {
            assertThat(chapters.get(i).getStartSec()).isGreaterThan(chapters.get(i - 1).getStartSec());
        }
    }

    @Test
    void 수강_여부와_리뷰_건수가_맞는다() {
        assertThat(lectures.findEnrollment(31, 1)).isPresent();
        assertThat(lectures.findEnrollment(31, 40)).isEmpty();

        assertThat(lectures.findReviewSummary(1).getReviewCount()).isEqualTo(board.countReviews(1));
        assertThat(board.findReviews(1, 0, 10)).isNotEmpty();
    }

    @Test
    void Q_A_답변_상태별_건수의_합이_전체와_같다() {
        int all = board.countQna(1, null);
        assertThat(board.countQna(1, "answered") + board.countQna(1, "waiting")).isEqualTo(all);
        assertThat(board.findQna(1, 0, 50, null)).hasSize(all);
    }

    @Test
    void 제출물을_번호로_찾는다() {
        assertThat(classroom.findSubmissionById(1)).isPresent();
        assertThat(classroom.findMySubmission(1, 31)).isPresent();
    }

    // ───────── 쓰기 (롤백) ─────────

    @Test
    void 리뷰를_쓰고_고치고_지운다() {
        assertThat(board.existsReview(2, 41)).isFalse();

        board.insertReview(2, 41, 5, "롤백되는 테스트 리뷰");
        assertThat(board.existsReview(2, 41)).isTrue();
        assertThat(board.updateReview(2, 41, 3, "수정")).isEqualTo(1);
        assertThat(board.deleteReview(2, 41)).isEqualTo(1);
        assertThat(board.existsReview(2, 41)).isFalse();
    }

    @Test
    void 과제를_내용만으로_제출하고_고치고_지운다() {
        int aid = classroom.insertSubmission(2, 41, null, null, "가".repeat(3000));
        assertThat(aid).isPositive();

        SubmissionRow row = classroom.findSubmissionById(aid).orElseThrow();
        assertThat(row.getNote()).hasSize(3000);
        assertThat(row.getWorkFilename()).isNull();

        assertThat(classroom.updateSubmission(aid, "/files/a.pdf", "a.pdf", "수정")).isEqualTo(1);
        assertThat(classroom.findWorkFile(aid)).isPresent();
        assertThat(classroom.deleteSubmission(aid)).isEqualTo(1);
        assertThat(classroom.findSubmissionById(aid)).isEmpty();
    }

    @Test
    void 이어보기_위치를_저장하고_수료는_한_번만_된다() {
        assertThat(classroom.updatePosition(2, 41, 120)).isEqualTo(1);
        assertThat(lectures.findEnrollment(41, 2).orElseThrow().getPosition()).isEqualTo(120);

        assertThat(classroom.markDone(2, 41)).isEqualTo(1);
        assertThat(classroom.markDone(2, 41)).isZero();
    }

    @Test
    void 질문을_쓰면_번호를_돌려준다() {
        int qid = board.insertQna(2, 41, "롤백되는 테스트 질문", "본문", true);
        assertThat(qid).isPositive();
        assertThat(board.findQnaDetail(2, qid)).isPresent();
    }
}
