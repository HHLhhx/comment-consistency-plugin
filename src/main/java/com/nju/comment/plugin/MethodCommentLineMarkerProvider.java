package com.nju.comment.plugin;

import com.intellij.codeInsight.daemon.LineMarkerInfo;
import com.intellij.codeInsight.daemon.LineMarkerProvider;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.markup.GutterIconRenderer;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.JBPopup;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.util.IconLoader;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.*;
import com.intellij.psi.javadoc.PsiDocComment;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.util.Arrays;

public class MethodCommentLineMarkerProvider implements LineMarkerProvider {
    @Override
    public @Nullable LineMarkerInfo<?> getLineMarkerInfo(@NotNull PsiElement psiElement) {
        // 始终找到最近的 PsiMethod（如果没有则返回）
        PsiMethod method = PsiTreeUtil.getParentOfType(psiElement, PsiMethod.class, false);
        if (method == null) {
            return null;
        }

        // 只在方法名标识符上显示图标，避免重复
        PsiElement nameIdentifier = method.getNameIdentifier();
        if (nameIdentifier == null || !nameIdentifier.equals(psiElement)) {
            return null;
        }

        Icon icon = IconLoader.getIcon("/icons/comment.png", MethodCommentLineMarkerProvider.class);
        return new LineMarkerInfo<>(
                nameIdentifier,
                nameIdentifier.getTextRange(),
                icon,
                psi -> "生成/更新注释",
                (e, elt) -> onIconClick(method),
                GutterIconRenderer.Alignment.RIGHT
        );
    }

    private void onIconClick(PsiMethod method) {
        Project project = method.getProject();

        SmartPsiElementPointer<PsiMethod> methodPointer =
                SmartPointerManager.getInstance(project).createSmartPsiElementPointer(method);

        //TODO
        String[] options = {"生成注释", "更新注释"};

        JBPopup popup = JBPopupFactory.getInstance()
                .createPopupChooserBuilder(Arrays.asList(options))
                .setTitle("生成注释")
                .setItemChosenCallback(selected -> ApplicationManager.getApplication().executeOnPooledThread(() -> {
                    String methodText = ReadAction.compute(() -> {
                        PsiMethod m = methodPointer.getElement();
                        return m == null ? null : m.getText();
                    });
                    if (methodText == null) {
                        return;
                    }

                    String generatedComment = CommentGeneratorClient.generateComment(methodText, selected);
                    if (generatedComment == null) {
                        return;
                    }

                    WriteCommandAction.runWriteCommandAction(project, () -> {
                        PsiMethod m = methodPointer.getElement();
                        if (m == null) {
                            return;
                        }
                        PsiDocComment doc = m.getDocComment();
                        PsiFile file = m.getContainingFile();
                        Document document = PsiDocumentManager.getInstance(project).getDocument(file);
                        if (document == null) {
                            return;
                        }
                        PsiDocumentManager.getInstance(project).doPostponedOperationsAndUnblockDocument(document);

                        if (doc != null) {
                            // 更新注释
                            TextRange range = doc.getTextRange();
                            document.replaceString(range.getStartOffset(), range.getEndOffset(), generatedComment);
                        } else {
                            // 生成注释
                            int methodStartOffset = m.getTextRange().getStartOffset();
                            document.insertString(methodStartOffset, generatedComment + "\n");
                        }
                        PsiDocumentManager.getInstance(project).commitDocument(document);
                    });
                }))
                .createPopup();

        Editor editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
        if (editor != null) {
            popup.showInBestPositionFor(editor);
        } else {
            popup.showInFocusCenter();
        }
    }
}
