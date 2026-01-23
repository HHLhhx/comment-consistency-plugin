package com.nju.comment.util;

import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;

import java.util.StringJoiner;

public final class MethodRecordUtil {

    private MethodRecordUtil() {
    }

    public static String buildMethodKey(PsiMethod method) {
        String qualifiedName = getQualifiedNameContainClass(method);
        String signature = getMethodSignature(method);
        return buildMethodKey(qualifiedName, signature);
    }

    public static String buildMethodKey(String qualifiedName, String signature) {
        return qualifiedName + "#" + signature;
    }

    private static String getParameterTypeText(PsiParameter param) {
        PsiType type = param.getType();
        try {
            return type.getCanonicalText();
        } catch (Exception ex) {
            return type.getPresentableText();
        }
    }

    public static String getQualifiedNameContainClass(PsiMethod method) {
        if (method == null) return "";
        PsiClass owner = method.getContainingClass();
        if (owner == null) return "";
        String qualifiedName = owner.getQualifiedName();
        return qualifiedName != null ? qualifiedName : "";
    }

    public static String getMethodSignature(PsiMethod method) {
        if (method == null) return "";
        StringJoiner sj = new StringJoiner(",");
        for (PsiParameter param : method.getParameterList().getParameters()) {
            sj.add(getParameterTypeText(param));
        }
        return method.getName() + "(" + sj + ")";
    }

    public static String getFilePath(PsiMethod method) {
        if (method == null || !method.isValid()) return null;
        PsiFile psiFile = method.getContainingFile();
        if (psiFile == null) return null;
        VirtualFile vf = psiFile.getVirtualFile();
        return vf != null ? vf.getPath() : null;
    }
}
