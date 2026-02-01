package com.nju.comment.history.state;

import com.intellij.psi.PsiMethod;
import com.nju.comment.dto.MethodRecord;
import com.nju.comment.dto.MethodStatus;
import com.nju.comment.util.TextProcessUtil;
import lombok.Getter;

import java.util.Objects;

/**
 * 承载状态机评估方法快照时所需的全部信息。
 */
@Getter
public final class MethodStateContext {

    private final PsiMethod psiMethod;
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

    public MethodStateContext(PsiMethod psiMethod,
                              MethodRecord record,
                              String currentMethod,
                              String currentComment,
                              String filePath,
                              String qualifiedName,
                              String signature) {
        this.psiMethod = psiMethod;
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

    /**
     * 确保记录持有指向 PSI 方法元素的有效指针。
     */
    public void ensurePointer(MethodRecord record) {
        if (record != null && record.getPointer() == null && psiMethod != null) {
            record.createMethodPointer(psiMethod);
        }
    }

    /**
     * 同步文件路径到记录，保持一致性。
     */
    public void syncFilePath(MethodRecord record) {
        if (record != null && filePath != null) {
            record.setFilePath(filePath);
        }
    }

    @Override
    public String toString() {
        return "MethodStateContext{" +
                "psiMethod=" + psiMethod +
                ", record=" + record +
                ", currentMethod='" + currentMethod + '\'' +
                ", currentComment='" + currentComment + '\'' +
                ", filePath='" + filePath + '\'' +
                ", qualifiedName='" + qualifiedName + '\'' +
                ", signature='" + signature + '\'' +
                ", oldMethod='" + oldMethod + '\'' +
                ", oldComment='" + oldComment + '\'' +
                ", stagedMethod='" + stagedMethod + '\'' +
                ", stagedComment='" + stagedComment + '\'' +
                '}';
    }
}
