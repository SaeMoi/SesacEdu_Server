package com.example.sesacedu.student.lecture.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class LectureDetailResponse {

    private final int lectureId;
    private final String name;
    private final String headline;
    private final String summary;
    private final String category;
    private final String level;
    private final String picture;
    private final String preview;
    private final Integer price;
    private final List<String> intro;
    private final InstructorPublicResponse instructor;
    private final List<OtherLectureResponse> otherLectures;
    private final Double rating;
    private final int reviewCount;
    private final Integer noticeCount;
    private final String viewerState;
    private final String action;
    private final Boolean done;
    private final Integer position;
    private final String learnUrl;
    private final String materialUrl;
    private final String assignmentUrl;

    private LectureDetailResponse(Builder builder) {
        this.lectureId = builder.lectureId;
        this.name = builder.name;
        this.headline = builder.headline;
        this.summary = builder.summary;
        this.category = builder.category;
        this.level = builder.level;
        this.picture = builder.picture;
        this.preview = builder.preview;
        this.price = builder.price;
        this.intro = builder.intro;
        this.instructor = builder.instructor;
        this.otherLectures = builder.otherLectures;
        this.rating = builder.rating;
        this.reviewCount = builder.reviewCount;
        this.noticeCount = builder.noticeCount;
        this.viewerState = builder.viewerState;
        this.action = builder.action;
        this.done = builder.done;
        this.position = builder.position;
        this.learnUrl = builder.learnUrl;
        this.materialUrl = builder.materialUrl;
        this.assignmentUrl = builder.assignmentUrl;
    }

    public int getLectureId() {
        return lectureId;
    }

    public String getName() {
        return name;
    }

    public String getHeadline() {
        return headline;
    }

    public String getSummary() {
        return summary;
    }

    public String getCategory() {
        return category;
    }

    public String getLevel() {
        return level;
    }

    public String getPicture() {
        return picture;
    }

    public String getPreview() {
        return preview;
    }

    public Integer getPrice() {
        return price;
    }

    public List<String> getIntro() {
        return intro;
    }

    public InstructorPublicResponse getInstructor() {
        return instructor;
    }

    public List<OtherLectureResponse> getOtherLectures() {
        return otherLectures;
    }

    public Double getRating() {
        return rating;
    }

    public int getReviewCount() {
        return reviewCount;
    }

    public Integer getNoticeCount() {
        return noticeCount;
    }

    public String getViewerState() {
        return viewerState;
    }

    public String getAction() {
        return action;
    }

    public Boolean getDone() {
        return done;
    }

    public Integer getPosition() {
        return position;
    }

    public String getLearnUrl() {
        return learnUrl;
    }

    public String getMaterialUrl() {
        return materialUrl;
    }

    public String getAssignmentUrl() {
        return assignmentUrl;
    }

    public static class Builder {
        private int lectureId;
        private String name;
        private String headline;
        private String summary;
        private String category;
        private String level;
        private String picture;
        private String preview;
        private Integer price;
        private List<String> intro;
        private InstructorPublicResponse instructor;
        private List<OtherLectureResponse> otherLectures;
        private Double rating;
        private int reviewCount;
        private Integer noticeCount;
        private String viewerState;
        private String action;
        private Boolean done;
        private Integer position;
        private String learnUrl;
        private String materialUrl;
        private String assignmentUrl;

        public Builder lectureId(int lectureId) {
            this.lectureId = lectureId;
            return this;
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder headline(String headline) {
            this.headline = headline;
            return this;
        }

        public Builder summary(String summary) {
            this.summary = summary;
            return this;
        }

        public Builder category(String category) {
            this.category = category;
            return this;
        }

        public Builder level(String level) {
            this.level = level;
            return this;
        }

        public Builder picture(String picture) {
            this.picture = picture;
            return this;
        }

        public Builder preview(String preview) {
            this.preview = preview;
            return this;
        }

        public Builder price(Integer price) {
            this.price = price;
            return this;
        }

        public Builder intro(List<String> intro) {
            this.intro = intro;
            return this;
        }

        public Builder instructor(InstructorPublicResponse instructor) {
            this.instructor = instructor;
            return this;
        }

        public Builder otherLectures(List<OtherLectureResponse> otherLectures) {
            this.otherLectures = otherLectures;
            return this;
        }

        public Builder rating(Double rating) {
            this.rating = rating;
            return this;
        }

        public Builder reviewCount(int reviewCount) {
            this.reviewCount = reviewCount;
            return this;
        }

        public Builder noticeCount(int noticeCount) {
            this.noticeCount = noticeCount;
            return this;
        }

        public Builder viewerState(String viewerState) {
            this.viewerState = viewerState;
            return this;
        }

        public Builder action(String action) {
            this.action = action;
            return this;
        }

        public Builder done(Boolean done) {
            this.done = done;
            return this;
        }

        public Builder position(Integer position) {
            this.position = position;
            return this;
        }

        public Builder learnUrl(String learnUrl) {
            this.learnUrl = learnUrl;
            return this;
        }

        public Builder materialUrl(String materialUrl) {
            this.materialUrl = materialUrl;
            return this;
        }

        public Builder assignmentUrl(String assignmentUrl) {
            this.assignmentUrl = assignmentUrl;
            return this;
        }

        public LectureDetailResponse build() {
            return new LectureDetailResponse(this);
        }
    }
}
