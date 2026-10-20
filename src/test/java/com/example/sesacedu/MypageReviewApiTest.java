package com.example.sesacedu;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Timestamp;
import java.util.Objects;

import static com.example.sesacedu.MypageLearningApiTest.kst;
import static com.example.sesacedu.MypageLearningApiTest.token;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * GET /mypage/{lectureid}/review (내 수강강좌 리뷰)와, 마이페이지 목록 3종(내 Q&A·내 학습·리뷰)의 기본 5개 페이지 번호 이동을 확인.
 *   gradlew test --tests com.example.sesacedu.MypageReviewApiTest
 */
@SuppressWarnings("null")
@SpringBootTest(properties = "JWT_SECRET=" + MypageLearningApiTest.SECRET)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MypageReviewApiTest {

    static final String SECRET = MypageLearningApiTest.SECRET;

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    long me;
    long[] writers = new long[7];

    @BeforeEach
    void setUp() {
        for (String t : new String[]{"qna", "reviews", "enrollments", "lectures", "instructors", "users"}) {
            jdbc.update("DELETE FROM " + t);
        }
        for (int i = 0; i < 7; i++) {
            jdbc.update("INSERT INTO users (mail, pw, uname, nickname, auth) VALUES (?, 'x', ?, ?, 1)",
                    "u" + i + "@example.test", "사용자" + i, "닉" + i);
            writers[i] = id("u" + i + "@example.test");
        }
        me = writers[2];

        jdbc.update("INSERT INTO instructors (user_id, iname) VALUES (9001, '박강사')");
        for (int l = 1; l <= 7; l++) {
            jdbc.update("INSERT INTO lectures (Lid, user_id, name, category, Lprice, status) VALUES (?, 9001, ?, '웹 보안', 10000, 'open')",
                    l, "강좌" + l);
        }

        // 강좌 1의 리뷰 7개: 작성자 i가 2026-10-0(i+1) 작성 → 최신순은 닉6, 닉5, ... 닉0
        for (int i = 0; i < 7; i++) {
            jdbc.update("INSERT INTO reviews (Lid, user_id, star, review, created) VALUES (1, ?, ?, ?, ?)",
                    writers[i], (i % 5) + 1, "리뷰 " + i, Timestamp.valueOf("2026-10-0" + (i + 1) + " 10:00:00"));
        }
        jdbc.update("UPDATE reviews SET updated = ? WHERE Lid = 1 AND user_id = ?", Timestamp.valueOf("2026-10-09 09:00:00"), me);
        jdbc.update("INSERT INTO reviews (Lid, user_id, star, review) VALUES (2, ?, 5, '다른 강좌')", writers[0]); // 강좌 2는 집계에서 빠져야 함

        // 내 학습·내 Q&A용: 나는 강좌 7개 수강, 질문 7개
        for (int l = 1; l <= 7; l++) {
            jdbc.update("INSERT INTO enrollments (user_id, Lid, done, enrolled_at) VALUES (?, ?, 0, ?)",
                    me, l, Timestamp.valueOf("2026-09-0" + l + " 10:00:00"));
            jdbc.update("INSERT INTO qna (Lid, user_id, title, question, isSecret, created) VALUES (?, ?, ?, '본문', 0, ?)",
                    l, me, "질문" + l, Timestamp.valueOf("2026-09-0" + l + " 11:00:00"));
        }
    }

    long id(String mail) {
        return Objects.requireNonNull(jdbc.queryForObject("SELECT user_id FROM users WHERE mail = ?", Long.class, mail));
    }

    static String bearer(long sub) {
        return "Bearer " + token(sub, -3600, SECRET);
    }

    // ------------------------------------------------------------ GET /mypage/{lectureid}/review

    @Test @DisplayName("리뷰 200: 기본 5개, 최신 작성순, 강좌·요약·내 리뷰·isMine, 페이지 정보")
    void reviewDefault() throws Exception {
        mvc.perform(get("/mypage/1/review").header("Authorization", bearer(me)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("MY_REVIEWS_FOUND"))
                .andExpect(jsonPath("$.result.lecture.lectureId").value(1))
                .andExpect(jsonPath("$.result.lecture.title").value("강좌1"))
                .andExpect(jsonPath("$.result.summary.count").value(7))
                .andExpect(jsonPath("$.result.summary.averageStar").value(2.6)) // (1+2+3+4+5+1+2)/7 = 2.57
                .andExpect(jsonPath("$.result.myReview.authorNickname").value("닉2"))
                .andExpect(jsonPath("$.result.myReview.star").value(3))
                .andExpect(jsonPath("$.result.myReview.review").value("리뷰 2"))
                .andExpect(jsonPath("$.result.myReview.createdAt").value(kst("2026-10-03 10:00:00")))
                .andExpect(jsonPath("$.result.myReview.updatedAt").value(kst("2026-10-09 09:00:00")))
                .andExpect(jsonPath("$.result.myReview.isMine").value(true))
                .andExpect(jsonPath("$.result.reviews", hasSize(5)))
                .andExpect(jsonPath("$.result.reviews[*].authorNickname", contains("닉6", "닉5", "닉4", "닉3", "닉2")))
                .andExpect(jsonPath("$.result.reviews[*].isMine", contains(false, false, false, false, true)))
                .andExpect(jsonPath("$.result.reviews[0].updatedAt").value(nullValue()))
                .andExpect(jsonPath("$.result.page.number").value(0))
                .andExpect(jsonPath("$.result.page.size").value(5))
                .andExpect(jsonPath("$.result.page.totalElements").value(7))
                .andExpect(jsonPath("$.result.page.totalPages").value(2))
                .andExpect(jsonPath("$.result.page.hasNext").value(true));
    }

    @Test @DisplayName("리뷰 200: 2페이지(page=1)는 나머지 2개, 내 리뷰는 페이지와 무관하게 유지")
    void reviewPage2() throws Exception {
        mvc.perform(get("/mypage/1/review?page=1").header("Authorization", bearer(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.reviews[*].authorNickname", contains("닉1", "닉0")))
                .andExpect(jsonPath("$.result.myReview.authorNickname").value("닉2"))
                .andExpect(jsonPath("$.result.page.number").value(1))
                .andExpect(jsonPath("$.result.page.hasNext").value(false));
    }

    @Test @DisplayName("리뷰 200: 리뷰 없는 강좌는 빈 목록, 평균 null, 내 리뷰 null")
    void reviewEmpty() throws Exception {
        mvc.perform(get("/mypage/3/review").header("Authorization", bearer(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.summary.count").value(0))
                .andExpect(jsonPath("$.result.summary.averageStar").value(nullValue()))
                .andExpect(jsonPath("$.result.myReview").value(nullValue()))
                .andExpect(jsonPath("$.result.reviews", hasSize(0)))
                .andExpect(jsonPath("$.result.page.totalPages").value(0));
    }

    @Test @DisplayName("리뷰 404: 없는 강좌 LECTURE_NOT_FOUND")
    void reviewNotFound() throws Exception {
        mvc.perform(get("/mypage/999/review").header("Authorization", bearer(me)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LECTURE_NOT_FOUND"));
    }

    @Test @DisplayName("리뷰 400: lectureId·page·size 오류를 result.errors로 모두")
    void reviewInvalid() throws Exception {
        mvc.perform(get("/mypage/abc/review?page=-1&size=51").header("Authorization", bearer(me)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.result.errors[*].field", contains("lectureId", "page", "size")));
        mvc.perform(get("/mypage/0/review").header("Authorization", bearer(me)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.result.errors[*].field", contains("lectureId")));
    }

    @Test @DisplayName("리뷰 401: 토큰 없음 UNAUTHORIZED, 만료 TOKEN_EXPIRED (입력 오류보다 먼저)")
    void reviewUnauthorized() throws Exception {
        mvc.perform(get("/mypage/abc/review"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mvc.perform(get("/mypage/1/review").header("Authorization", "Bearer " + token(me, 60, SECRET)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TOKEN_EXPIRED"));
    }

    // ------------------------------------------------------------ 기본 5개 페이지 (내 Q&A, 내 학습)

    @Test @DisplayName("내 Q&A: 기본 5개, page=1은 나머지 2개")
    void qnaDefaultFive() throws Exception {
        mvc.perform(get("/mypage/qna").header("Authorization", bearer(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.questions", hasSize(5)))
                .andExpect(jsonPath("$.result.questions[0].title").value("질문7"))
                .andExpect(jsonPath("$.result.page.size").value(5))
                .andExpect(jsonPath("$.result.page.totalElements").value(7))
                .andExpect(jsonPath("$.result.page.totalPages").value(2))
                .andExpect(jsonPath("$.result.page.hasNext").value(true));
        mvc.perform(get("/mypage/qna?sort=RECENT&page=1").header("Authorization", bearer(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.questions[*].title", contains("질문2", "질문1")))
                .andExpect(jsonPath("$.result.page.hasNext").value(false));
    }

    @Test @DisplayName("내 학습: 기본 5개, page=1은 나머지 2개")
    void coursesDefaultFive() throws Exception {
        mvc.perform(get("/mypage/courses?status=ALL").header("Authorization", bearer(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.courses", hasSize(5)))
                .andExpect(jsonPath("$.result.page.size").value(5))
                .andExpect(jsonPath("$.result.page.totalElements").value(7))
                .andExpect(jsonPath("$.result.page.totalPages").value(2));
        mvc.perform(get("/mypage/courses?page=1").header("Authorization", bearer(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.courses", hasSize(2)))
                .andExpect(jsonPath("$.result.page.hasNext").value(false));
    }
}
