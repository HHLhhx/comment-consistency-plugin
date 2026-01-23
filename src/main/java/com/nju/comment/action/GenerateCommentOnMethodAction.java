package com.nju.comment.action;

import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.editor.Caret;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import com.nju.comment.service.PluginProjectService;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;

@Slf4j
public class GenerateCommentOnMethodAction extends AnAction {

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        Project project = e.getProject();
        if (editor == null || project == null) {
            return;
        }

        PsiFile psiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.getDocument());
        if (psiFile == null) {
            return;
        }

        int offset = editor.getCaretModel().getOffset();
        PsiElement element = psiFile.findElementAt(offset);
        PsiMethod method = PsiTreeUtil.getParentOfType(element, PsiMethod.class);
        if (method == null) {
            return;
        }

        PluginProjectService service = project.getService(PluginProjectService.class);
        service.refreshMethodHistory(method);
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        Presentation presentation = e.getPresentation();

        PsiElement element = e.getData(CommonDataKeys.PSI_ELEMENT);

        if (element == null) {
            PsiFile file = e.getData(CommonDataKeys.PSI_FILE);
            Caret caret = e.getData(CommonDataKeys.CARET);
            if (file != null && caret != null) {
                int offset = caret.getOffset();
                element = file.findElementAt(offset);
            }
        }

        boolean visible = PsiTreeUtil.getParentOfType(element, PsiMethod.class) != null;
        presentation.setEnabledAndVisible(visible);
    }
}
