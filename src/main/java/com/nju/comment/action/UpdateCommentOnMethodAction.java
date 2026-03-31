package com.nju.comment.action;

import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Caret;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import com.nju.comment.service.AuthManager;
import com.nju.comment.pojo.MethodStatus;
import com.nju.comment.service.PluginProjectService;
import com.nju.comment.util.MethodValidationUtil;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;

@Slf4j
public class UpdateCommentOnMethodAction extends AnAction {

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        if (!AuthManager.isLoggedIn()) {
            return;
        }

        Editor editor = e.getData(CommonDataKeys.EDITOR);
        Project project = e.getProject();
        if (editor == null || project == null) {
            return;
        }

        PluginProjectService service = project.getService(PluginProjectService.class);
        if (service.isAutoUpdateEnabled()) return;

        PsiFile psiFile = ReadAction.compute(() ->
                PsiDocumentManager.getInstance(project).getPsiFile(editor.getDocument()));
        if (psiFile == null) {
            return;
        }

        int offset = editor.getCaretModel().getOffset();
        PsiElement element = ReadAction.compute(() -> psiFile.findElementAt(offset));
        PsiMethod method = ReadAction.compute(() -> PsiTreeUtil.getParentOfType(element, PsiMethod.class));

        if (!MethodValidationUtil.isQuicklyEligible(method)) {
            return;
        }

        if (MethodStatus.UNCHANGED.equals(service.preCheckChange(method))
                || MethodStatus.NEW_METHOD_WITHOUT_COMMENT.equals(service.preCheckChange(method))) {
            return;
        }

        service.refreshMethodHistory(method);
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        Presentation presentation = e.getPresentation();

        if (!AuthManager.isLoggedIn()) {
            presentation.setEnabledAndVisible(false);
            return;
        }

        Project project = e.getProject();
        if (project == null) {
            presentation.setEnabledAndVisible(false);
            return;
        }

        PluginProjectService service = project.getService(PluginProjectService.class);
        if (service.isAutoUpdateEnabled()) {
            presentation.setEnabledAndVisible(false);
            return;
        }

        boolean visible = ReadAction.compute(() -> {
            PsiElement element = e.getData(CommonDataKeys.PSI_ELEMENT);
            if (element == null) {
                PsiFile file = e.getData(CommonDataKeys.PSI_FILE);
                Caret caret = e.getData(CommonDataKeys.CARET);
                if (file != null && caret != null) {
                    int offset = caret.getOffset();
                    element = file.findElementAt(offset);
                }
            }
            PsiMethod method = PsiTreeUtil.getParentOfType(element, PsiMethod.class);

            if (!MethodValidationUtil.isQuicklyEligible(method)) {
                return false;
            }

            return !MethodStatus.UNCHANGED.equals(service.preCheckChange(method))
                    && !MethodStatus.NEW_METHOD_WITHOUT_COMMENT.equals(service.preCheckChange(method));
        });

        presentation.setEnabledAndVisible(visible);
    }
}
