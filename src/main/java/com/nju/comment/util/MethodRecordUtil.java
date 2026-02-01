package com.nju.comment.util;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;

import java.util.StringJoiner;

public final class MethodRecordUtil {

    private MethodRecordUtil() {
    }

    public static String buildMethodKey(PsiMethod method) {
        if (method == null) return "";
        return ReadAction.compute(() -> buildMethodKeyUnsafely(method));
    }

    public static String buildMethodKey(String qualifiedName, String signature) {
        return qualifiedName + "#" + signature;
    }

    public static String getQualifiedNameContainClass(PsiMethod method) {
        if (method == null) return "";
        return ReadAction.compute(() -> getQualifiedNameContainClassUnsafely(method));
    }

    public static String getMethodSignature(PsiMethod method) {
        if (method == null) return "";
        return ReadAction.compute(() -> getMethodSignatureUnsafely(method));
    }

    public static String getFilePath(PsiMethod method) {
        if (method == null) return null;
        return ReadAction.compute(() -> getFilePathUnsafely(method));
    }

    private static String buildMethodKeyUnsafely(PsiMethod method) {
        String qualifiedName = getQualifiedNameContainClassUnsafely(method);
        String signature = getMethodSignatureUnsafely(method);
        return buildMethodKey(qualifiedName, signature);
    }

    private static String getQualifiedNameContainClassUnsafely(PsiMethod method) {
        if (method == null) return "";
        PsiClass owner = method.getContainingClass();
        if (owner == null) return "";
        String qualifiedName = owner.getQualifiedName();
        return qualifiedName != null ? qualifiedName : "";
    }

    private static String getMethodSignatureUnsafely(PsiMethod method) {
        if (method == null) return "";
        StringJoiner sj = new StringJoiner(",");
        for (PsiParameter param : method.getParameterList().getParameters()) {
            sj.add(getParameterTypeTextUnsafely(param));
        }
        return method.getName() + "(" + sj + ")";
    }

    private static String getParameterTypeTextUnsafely(PsiParameter param) {
        if (param == null) return "";
        PsiType type = param.getType();
        try {
            return type.getCanonicalText();
        } catch (Exception ex) {
            return type.getPresentableText();
        }
    }

    private static String getFilePathUnsafely(PsiMethod method) {
        if (method == null || !method.isValid()) return null;
        PsiFile psiFile = method.getContainingFile();
        if (psiFile == null) return null;
        VirtualFile vf = psiFile.getVirtualFile();
        return vf != null ? vf.getPath() : null;
    }
}
