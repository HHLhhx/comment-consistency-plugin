package com.nju.comment.action;

import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Caret;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.util.PsiTreeUtil;
import com.nju.comment.dto.MethodStatus;
import com.nju.comment.service.PluginProjectService;
import org.jetbrains.annotations.NotNull;

public class GenerateCommentOnMethodAction extends AnAction {

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        Project project = e.getProject();
        if (editor == null || project == null) {
            return;
        }

        PsiFile psiFile = ReadAction.compute(() ->
                PsiDocumentManager.getInstance(project).getPsiFile(editor.getDocument()));
        if (psiFile == null) {
            return;
        }

        int offset = editor.getCaretModel().getOffset();
        PsiElement element = ReadAction.compute(() -> psiFile.findElementAt(offset));
        PsiMethod method = ReadAction.compute(() -> PsiTreeUtil.getParentOfType(element, PsiMethod.class));
        if (method == null) {
            return;
        }

        PluginProjectService service = project.getService(PluginProjectService.class);
        service.generateComment(method);
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        Presentation presentation = e.getPresentation();

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
            if (method == null) return false;

            Project project = e.getProject();
            if (project == null) return false;
            PluginProjectService service = project.getService(PluginProjectService.class);
            MethodStatus status = service.getMethodStatus(method);
            return MethodStatus.NEW_METHOD_WITHOUT_COMMENT.equals(status);
        });

        presentation.setEnabledAndVisible(visible);
    }
}
