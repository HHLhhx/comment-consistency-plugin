package com.nju.comment.pojo;

import com.intellij.psi.PsiMethod;
import com.intellij.psi.SmartPointerManager;
import com.intellij.psi.SmartPsiElementPointer;
import com.nju.comment.util.MethodRecordUtil;
import lombok.Data;

import java.time.Instant;

@Data
public class MethodRecord {
    String key;
    transient SmartPsiElementPointer<PsiMethod> pointer;
    volatile MethodStatus status;

    volatile String oldMethod;
    volatile String oldComment;
    volatile Instant updatedAt;

    String filePath;
    String qualifiedNameContainClass;
    String signature;

    volatile String stagedMethod;
    volatile String stagedComment;

    public MethodRecord(String qualifiedNameContainClass, String signature, String oldMethod, String oldComment) {
        this.qualifiedNameContainClass = qualifiedNameContainClass;
        this.signature = signature;
        this.oldMethod = oldMethod;
        this.oldComment = oldComment;
        this.updatedAt = Instant.now();
        this.filePath = null;
        this.stagedComment = null;

        this.key = MethodRecordUtil.buildMethodKey(qualifiedNameContainClass, signature);
    }

    public void createMethodPointer(PsiMethod psiMethod) {
        this.pointer = SmartPointerManager.getInstance(psiMethod.getProject()).createSmartPsiElementPointer(psiMethod);
    }

    public void touch() {
        this.updatedAt = Instant.now();
    }

    public void copyStagedToOldComment() {
        if (this.stagedComment != null) {
            this.oldComment = this.stagedComment;
            touch();
        }
    }

    public void clearStagedComment() {
        this.stagedComment = null;
        touch();
    }

    public void copyStagedToOldMethod() {
        if (this.stagedMethod != null) {
            this.oldMethod = this.stagedMethod;
            touch();
        }
    }

    public void clearStagedMethod() {
        this.stagedMethod = null;
        touch();
    }
}
