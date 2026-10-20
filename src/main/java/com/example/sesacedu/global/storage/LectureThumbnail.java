package com.example.sesacedu.global.storage;

/**
 * 강의 썸네일 주소 규칙.
 * - 업로드한 썸네일: {app.storage.root}/lecture/picture/ 에 저장하고 DB lectures.picture 에는 /images/lecture/{파일명} 을 넣는다.
 * - 썸네일이 없으면 서버 기본 이미지(classpath:/static/images/lecture/default-thumbnail.png) 주소를 내려준다.
 */
public final class LectureThumbnail {

    public static final String URL_PATH = "/images/lecture/";
    public static final String STORAGE_PREFIX = "lecture/picture";
    public static final String DEFAULT_URL = URL_PATH + "default-thumbnail.png";

    private LectureThumbnail() {
    }

    /** DB 의 picture 값 → 응답 주소. 비어 있으면 기본 썸네일. */
    public static String urlOf(String picture) {
        return (picture == null || picture.isBlank()) ? DEFAULT_URL : picture;
    }
}
