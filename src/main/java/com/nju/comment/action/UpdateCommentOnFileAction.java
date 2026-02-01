package com.nju.comment.action;

import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.nju.comment.service.PluginProjectService;
import org.jetbrains.annotations.NotNull;

public class UpdateCommentOnFileAction extends AnAction {

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

        VirtualFile vf = ReadAction.compute(psiFile::getVirtualFile);
        if (vf == null) {
            return;
        }

        PluginProjectService service = project.getService(PluginProjectService.class);
        service.refreshFileMethodHistories(vf);
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        Presentation presentation = e.getPresentation();

        PsiFile psiFile = e.getData(CommonDataKeys.PSI_FILE);

        boolean visible = ReadAction.compute(() -> {
            if (psiFile == null) return false;
            VirtualFile vf = psiFile.getVirtualFile();
            return vf != null && "java".equalsIgnoreCase(vf.getExtension());
        });

        presentation.setEnabledAndVisible(visible);
    }
}
