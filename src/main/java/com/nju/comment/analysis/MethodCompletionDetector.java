package com.nju.comment.analysis;

import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import lombok.Data;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

public class MethodCompletionDetector {

    public static MethodCompletionResult checkMethodCompletion(@NotNull PsiMethod psiMethod) {
        MethodCompletionResult result = new MethodCompletionResult();

        result.setHasSyntaxErrors(hasSyntaxErrors(psiMethod));
        result.setSignatureComplete(isSignatureComplete(psiMethod));
        result.setAllPathsReturn(checkAllPathsReturn(psiMethod));
        result.setHasTodoComments(hasTodoComments(psiMethod));
        result.setUnHandledExceptions(getUnHandledExceptions(psiMethod));
        result.setUnInitializedVariables(getUnInitializedVariables(psiMethod));

        return result;
    }

    private static List<String> getUnInitializedVariables(@NotNull PsiMethod psiMethod) {
        return null;
    }

    private static List<String> getUnHandledExceptions(@NotNull PsiMethod psiMethod) {
        return null;
    }

    private static boolean hasTodoComments(@NotNull PsiMethod psiMethod) {
        return false;
    }

    private static boolean checkAllPathsReturn(@NotNull PsiMethod psiMethod) {
        return false;
    }

    private static boolean isSignatureComplete(@NotNull PsiMethod psiMethod) {
        PsiIdentifier nameId = psiMethod.getNameIdentifier();
        if (nameId == null || nameId.getText().trim().isEmpty()) return false;

        if (!psiMethod.isConstructor()) {
            if (psiMethod.getReturnTypeElement() == null) return false;
        }

        for (PsiParameter param : psiMethod.getParameterList().getParameters()) {
            if (param.getTypeElement() == null) return false;
            if (param.getName() == null || param.getName().trim().isEmpty()) return false;
        }

        for (PsiJavaCodeReferenceElement ref : psiMethod.getThrowsList().getReferenceElements()) {
            if (ref == null || ref.getReferenceNameElement() == null) return false;
        }

        return true;
    }

    private static boolean hasSyntaxErrors(@NotNull PsiMethod psiMethod) {
        return PsiTreeUtil.findChildOfType(psiMethod, PsiErrorElement.class) != null;
    }

    private static class ReturnPathAnalyzer {

        private static class ReturnVisitor extends JavaRecursiveElementVisitor {

        }
    }

    private static class ExceptionFinder extends JavaRecursiveElementVisitor {

    }

    @Data
    public static class MethodCompletionResult {
        private boolean hasSyntaxErrors;
        private boolean signatureComplete;
        private boolean allPathsReturn;
        private boolean hasTodoComments;
        private List<String> unHandledExceptions = new ArrayList<>();
        private List<String> unInitializedVariables = new ArrayList<>();

        public boolean isComplete() {
            return !hasSyntaxErrors &&
                    signatureComplete &&
                    allPathsReturn &&
                    !hasTodoComments &&
                    unHandledExceptions.isEmpty() &&
                    unInitializedVariables.isEmpty();
        }

        public String generateReport() {
            StringBuilder report = new StringBuilder();
            report.append("函数完成度检查报告:\n");
            report.append("===================\n");

            if (hasSyntaxErrors) {
                report.append("✗ 存在语法错误\n");
            }
            if (!signatureComplete) {
                report.append("✗ 函数签名不完整\n");
            }
            if (!allPathsReturn) {
                report.append("✗ 非所有路径都有返回值\n");
            }
            if (hasTodoComments) {
                report.append("✗ 存在TODO/FIXME注释\n");
            }
            if (!unHandledExceptions.isEmpty()) {
                report.append("✗ 未处理的异常: ").append(unHandledExceptions).append("\n");
            }
            if (!unInitializedVariables.isEmpty()) {
                report.append("✗ 未初始化的变量: ").append(unInitializedVariables).append("\n");
            }

            if (isComplete()) {
                report.append("\n✓ 函数编写完成！\n");
            } else {
                report.append("\n✗ 函数尚未完成\n");
            }

            return report.toString();
        }
    }
}
