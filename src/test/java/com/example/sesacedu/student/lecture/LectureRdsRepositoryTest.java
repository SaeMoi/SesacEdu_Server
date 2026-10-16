package com.example.sesacedu.student.lecture;

import org.junit.jupiter.api.Test;
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
@SpringBootTest
@Transactional
class LectureRdsRepositoryTest {

    @Autowired LectureRepository lectures;

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
    void 수강_여부와_리뷰_건수가_맞는다() {
        assertThat(lectures.findEnrollment(31, 1)).isPresent();
        assertThat(lectures.findEnrollment(31, 40)).isEmpty();

        assertThat(lectures.findReviewSummary(1).getReviewCount()).isPositive();
    }






}
