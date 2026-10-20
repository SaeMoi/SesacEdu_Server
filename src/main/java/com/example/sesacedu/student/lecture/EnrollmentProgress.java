package com.example.sesacedu.student.lecture;

public class EnrollmentProgress {

    private final boolean done;
    private final Integer position;

    public EnrollmentProgress(boolean done, Integer position) {
        this.done = done;
        this.position = position;
    }

    public boolean isDone() {
        return done;
    }

    public Integer getPosition() {
        return position;
    }
}
