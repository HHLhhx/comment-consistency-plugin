package com.nju.comment.history.state;

import com.nju.comment.pojo.MethodRecord;
import com.nju.comment.pojo.MethodStatus;
import com.nju.comment.util.TextProcessUtil;
import lombok.Getter;

import java.util.Objects;

@Getter
public final class MethodStateContext {

    private final MethodRecord record;

    private final String currentMethod;
    private final String currentComment;

    private final String filePath;
    private final String qualifiedName;
    private final String signature;

    private final String oldMethod;
    private final String oldComment;
    private final String stagedMethod;
    private final String stagedComment;

    public MethodStateContext(MethodRecord record,
                              String currentMethod,
                              String currentComment,
                              String filePath,
                              String qualifiedName,
                              String signature) {
        this.record = record;
        this.currentMethod = TextProcessUtil.processMethod(currentMethod);
        this.currentComment = TextProcessUtil.processComment(currentComment);
        this.filePath = filePath;
        this.qualifiedName = qualifiedName;
        this.signature = signature;

        if (record != null) {
            this.oldMethod = TextProcessUtil.processMethod(record.getOldMethod());
            this.oldComment = TextProcessUtil.processComment(record.getOldComment());
            this.stagedMethod = TextProcessUtil.processMethod(record.getStagedMethod());
            this.stagedComment = TextProcessUtil.processComment(record.getStagedComment());
        } else {
            this.oldMethod = null;
            this.oldComment = null;
            this.stagedMethod = null;
            this.stagedComment = null;
        }
    }

    public boolean hasRecord() {
        return record != null;
    }

    public boolean hasCurrentComment() {
        return !TextProcessUtil.safeTrimNullable(currentComment).isEmpty();
    }

    public boolean commentEqualsOld() {
        return Objects.equals(currentComment, oldComment);
    }

    public boolean commentEqualsStaged() {
        return Objects.equals(currentComment, stagedComment);
    }

    public boolean methodEqualsOld() {
        return Objects.equals(currentMethod, oldMethod);
    }

    public boolean methodEqualsStaged() {
        return Objects.equals(currentMethod, stagedMethod);
    }

    public MethodStatus getCurMethodStatus() {
        return hasRecord() ? record.getStatus() : MethodStatus.UNDEFINED;
    }

    public void setCurMethodStatus(MethodStatus status) {
        if (hasRecord()) {
            record.setStatus(status);
        }
    }

    public void syncFilePath(MethodRecord record) {
        if (record != null && filePath != null) {
            record.setFilePath(filePath);
        }
    }
}
