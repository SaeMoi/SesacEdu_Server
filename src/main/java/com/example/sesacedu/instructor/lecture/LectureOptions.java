package com.example.sesacedu.instructor.lecture;

import java.util.List;

/**
 * 강의 카테고리·난이도 목록. 강사 등록 화면의 선택 목록과 같은 순서다.
 * 난이도는 DB 에 "입문" 처럼 저장하고(강의 목록 필터 값), 화면에는 "입문 과정"으로 보여 준다.
 */
public final class LectureOptions {

    public static final List<String> CATEGORIES =
            List.of("웹 보안", "네트워크", "개발 기초", "로그 분석", "클라우드");

    public static final List<String> LEVELS =
            List.of("입문", "초급", "중급", "고급");

    private LectureOptions() {
    }

    public static boolean isCategory(String value) {
        return value != null && CATEGORIES.contains(value.trim());
    }

    public static boolean isLevel(String value) {
        return value != null && LEVELS.contains(value.trim());
    }
}
