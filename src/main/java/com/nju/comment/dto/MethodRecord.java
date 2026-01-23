package com.nju.comment.dto;

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

    String oldMethod;
    String oldComment;
    Instant updatedAt;
    int tag;

    String filePath;
    String qualifiedNameContainClass;
    String signature;

    String stagedComment;

    public MethodRecord(String qualifiedNameContainClass, String signature, String oldMethod, String oldComment) {
        this.qualifiedNameContainClass = qualifiedNameContainClass;
        this.signature = signature;
        this.oldMethod = oldMethod;
        this.oldComment = oldComment;
        this.updatedAt = Instant.now();
        this.tag = 0;
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

    public void revertStagedOldComment() {
        if (this.stagedComment != null) {
            this.oldComment = this.stagedComment;
            this.stagedComment = null;
            touch();
        }
    }

    public void clearStaged() {
        this.stagedComment = null;
        touch();
    }
}
