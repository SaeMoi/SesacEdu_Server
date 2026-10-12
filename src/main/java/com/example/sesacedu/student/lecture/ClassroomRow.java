package com.example.sesacedu.student.lecture;

/**
 * 수강 화면(영상 · 자료 · 과제)에서 함께 쓰는 강의 한 행.
 *
 * DB 에서 꺼낸 값을 담기만 하는 객체다. 응답으로 나갈 모양은 dto 쪽에서 정한다.
 * 영상과 자료의 저장 경로(video · material)를 들고 있으므로 이 객체를 그대로
 * 직렬화하면 안 된다. 권한이 없는 사람에게 경로가 새어 나간다.
 */
public class ClassroomRow {

    private final int lid;
    private final String name;
    private final String video;
    private final String videoFilename;
    private final Integer duration;
    private final String material;
    private final String materialFilename;
    private final String assignmentTitle;
    private final String assignmentContent;
    private final String assignmentFileType;

    public ClassroomRow(int lid, String name,
                        String video, String videoFilename, Integer duration,
                        String material, String materialFilename,
                        String assignmentTitle, String assignmentContent, String assignmentFileType) {
        this.lid = lid;
        this.name = name;
        this.video = video;
        this.videoFilename = videoFilename;
        this.duration = duration;
        this.material = material;
        this.materialFilename = materialFilename;
        this.assignmentTitle = assignmentTitle;
        this.assignmentContent = assignmentContent;
        this.assignmentFileType = assignmentFileType;
    }

    public int getLid() {
        return lid;
    }

    public String getName() {
        return name;
    }

    /** 영상 저장 경로. 응답에 넣지 않는다. 값이 있는지 확인하는 용도다. */
    public String getVideo() {
        return video;
    }

    public String getVideoFilename() {
        return videoFilename;
    }

    public Integer getDuration() {
        return duration;
    }

    /** 자료 저장 경로. 응답에 넣지 않는다. */
    public String getMaterial() {
        return material;
    }

    public String getMaterialFilename() {
        return materialFilename;
    }

    public String getAssignmentTitle() {
        return assignmentTitle;
    }

    public String getAssignmentContent() {
        return assignmentContent;
    }

    public String getAssignmentFileType() {
        return assignmentFileType;
    }
}
