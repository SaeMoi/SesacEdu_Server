package com.example.sesacedu.student.lecture.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 강의 Q&A 목록. GET /lectures/{Lid}/qna
 *
 * 공개 API 다. 비밀글도 목록에는 나오지만 제목이 가려진다.
 * 목록에서 비밀글을 통째로 빼지 않는 이유는 두 가지다. 번호가 비면 그 자리에 글이
 * 있다는 게 드러나고, 전체 건수가 보는 사람마다 달라져 페이지 계산이 어긋난다.
 */
public class QnaListResponse {

    private final List<QnaItem> questions;
    private final PageResponse page;

    public QnaListResponse(List<QnaItem> questions, PageResponse page) {
        this.questions = questions;
        this.page = page;
    }

    public List<QnaItem> getQuestions() {
        return questions;
    }

    public PageResponse getPage() {
        return page;
    }

    /**
     * Q&A 목록 한 건.
     *
     * secret 이 true 이고 accessible 이 false 면 title 은 실제 제목이 아니라
     * 고정 문구다. 본문은 애초에 조회하지 않으므로 이 응답에 담길 일이 없다.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class QnaItem {

        private final int qid;
        private final String title;
        private final String nickname;
        private final boolean secret;
        private final boolean accessible;
        private final boolean answered;
        private final LocalDateTime created;

        public QnaItem(int qid, String title, String nickname, boolean secret,
                       boolean accessible, boolean answered, LocalDateTime created) {
            this.qid = qid;
            this.title = title;
            this.nickname = nickname;
            this.secret = secret;
            this.accessible = accessible;
            this.answered = answered;
            this.created = created;
        }

        public int getQid() {
            return qid;
        }

        /** 볼 수 없는 비밀글이면 고정 문구로 바뀐 값이 들어 있다. */
        public String getTitle() {
            return title;
        }

        public String getNickname() {
            return nickname;
        }

        /** 비밀글 표시. 자물쇠 아이콘을 그리는 데 쓴다. */
        public boolean isSecret() {
            return secret;
        }

        /** 비밀글을 볼 수 있는지. false 면 제목이 가려져 있다. */
        public boolean isAccessible() {
            return accessible;
        }

        /** 답변이 달렸는지. answer 가 아니라 이 불린만 내보낸다. */
        public boolean isAnswered() {
            return answered;
        }

        public LocalDateTime getCreated() {
            return created;
        }
    }
}
