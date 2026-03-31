package com.nju.comment.util;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiComment;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiType;
import com.intellij.psi.PsiWhiteSpace;
import com.intellij.psi.util.PsiTreeUtil;
import com.nju.comment.pojo.MethodRecord;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
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

    public static long getSourceStamp(PsiMethod method) {
        if (method == null) return -1L;
        return ReadAction.compute(() -> {
            if (!method.isValid()) return -1L;
            PsiFile psiFile = method.getContainingFile();
            if (psiFile == null) return -1L;
            VirtualFile vf = psiFile.getVirtualFile();
            return vf != null ? vf.getModificationStamp() : -1L;
        });
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

    public static PsiMethod resolveMethod(Project project, MethodRecord record) {
        if (project == null || record == null || record.getFilePath() == null) {
            return null;
        }
        return ReadAction.compute(() -> resolveMethodUnsafely(project, record));
    }

    private static PsiMethod resolveMethodUnsafely(Project project, MethodRecord record) {
        VirtualFile vf = LocalFileSystem.getInstance().findFileByPath(record.getFilePath());
        if (vf == null || !vf.isValid()) {
            return null;
        }
        PsiFile psiFile = PsiManager.getInstance(project).findFile(vf);
        if (psiFile == null) {
            return null;
        }
        Collection<PsiMethod> methods = PsiTreeUtil.collectElementsOfType(psiFile, PsiMethod.class);
        for (PsiMethod method : methods) {
            String key = buildMethodKeyUnsafely(method);
            if (record.getKey().equals(key)) {
                return method;
            }
        }
        return null;
    }

    public static PsiFile resolvePsiFile(Project project, String filePath) {
        if (project == null || filePath == null || filePath.isBlank()) {
            return null;
        }
        return ReadAction.compute(() -> {
            VirtualFile vf = LocalFileSystem.getInstance().findFileByPath(filePath);
            if (vf == null || !vf.isValid()) {
                return null;
            }
            return PsiManager.getInstance(project).findFile(vf);
        });
    }

    public static @NotNull String getMethodTextWithoutComments(PsiMethod method) {
        return ReadAction.compute(() -> {
            PsiFile containingFile = method.getContainingFile();
            if (containingFile == null) {
                return "";
            }

            PsiElement firstChild = method.getFirstChild();
            while (firstChild instanceof PsiComment ||
                    firstChild instanceof PsiWhiteSpace) {
                firstChild = firstChild.getNextSibling();
            }

            String mtd = "";
            if (firstChild != null) {
                int methodStartOffset = firstChild.getTextRange().getStartOffset();
                int endOffset = method.getTextRange().getEndOffset();
                mtd = containingFile.getText().substring(methodStartOffset, endOffset).trim();
            }
            return mtd;
        });
    }
}
