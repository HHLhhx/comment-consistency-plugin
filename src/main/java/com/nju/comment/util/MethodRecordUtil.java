package com.nju.comment.util;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import com.nju.comment.dto.MethodRecord;

import java.util.Collection;
import java.util.StringJoiner;

public final class MethodRecordUtil {

    private MethodRecordUtil() {
    }

    public static SmartPsiElementPointer<PsiMethod> createSmartPointer(Project project, MethodRecord record) {
        return ReadAction.compute(() -> {
            if (project == null || record == null) return null;
            String path = record.getFilePath();
            if (path == null || path.isEmpty()) return null;

            VirtualFile vf = LocalFileSystem.getInstance().findFileByPath(path);
            if (vf == null || !vf.exists()) return null;

            PsiFile psiFile = PsiManager.getInstance(project).findFile(vf);
            if (psiFile == null) return null;

            String classQualified = record.getQualifiedNameContainClass();
            String signature = record.getSignature();
            if (classQualified == null || classQualified.isEmpty() || signature == null || signature.isEmpty()) {
                return null;
            }

            Collection<PsiClass> classes = PsiTreeUtil.collectElementsOfType(psiFile, PsiClass.class);
            for (PsiClass psiClass : classes) {
                String qualifiedName = psiClass.getQualifiedName();
                if (qualifiedName == null) continue;
                if (qualifiedName.equals(classQualified)) {
                    for (PsiMethod method : psiClass.getMethods()) {
                        String key = buildMethodKey(method);
                        String expectedKey = buildMethodKey(qualifiedName, signature);
                        if (expectedKey.equals(key)) {
                            return SmartPointerManager.getInstance(project).createSmartPsiElementPointer(method);
                        }
                    }
                    return null;
                }
            }

            return null;
        });
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
}
