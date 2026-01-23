package com.nju.comment.util;

import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;

public final class LocationUtil {

    private LocationUtil() {
    }

    public static String getFilePath(PsiMethod method) {
        if (method == null || !method.isValid()) return null;
        PsiFile psiFile = method.getContainingFile();
        if (psiFile == null) return null;
        VirtualFile vf = psiFile.getVirtualFile();
        return vf != null ? vf.getPath() : null;
    }
}
