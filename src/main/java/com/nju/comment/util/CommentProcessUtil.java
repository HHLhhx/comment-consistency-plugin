package com.nju.comment.util;

import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;

public final class CommentProcessUtil {

    private CommentProcessUtil() {
    }

    public static String processComment(String comment, PsiMethod psiMethod) {
        String sep = "\n";
        PsiFile file = psiMethod.getContainingFile();
        if (file != null) {
            String fileText = file.getText();
            if (fileText.contains("\r\n")) {
                sep = "\r\n";
            } else if (fileText.contains("\r")) {
                sep = "\r";
            }
        }

        // 统一所有已有分隔符为目标分隔符
        String processed = comment == null ? "" : comment.trim();
        processed = processed.replace("\r\n", sep).replace("\r", sep);

        return processed;
    }

    public static String safeTrim(String s) {
        return s == null ? "" : s.trim();
    }

    public static String safeTrimNullable(String s) {
        return (s == null || s.trim().isEmpty()) ? "" : s.trim();
    }
}
