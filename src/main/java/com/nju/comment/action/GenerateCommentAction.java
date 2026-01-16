package com.nju.comment.action;

import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.javadoc.PsiDocComment;
import com.intellij.psi.util.PsiTreeUtil;
import com.nju.comment.client.global.CommentGeneratorClient;
import com.nju.comment.dto.MethodData;
import com.nju.comment.dto.MethodOptions;
import com.nju.comment.tool.CommentProcessUtil;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;

@Slf4j
public class GenerateCommentAction extends AnAction {

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

        //创建方法的智能指针
        SmartPsiElementPointer<PsiMethod> spp = SmartPointerManager.getInstance(project).createSmartPsiElementPointer(method);

        //在后台线程中处理注释生成
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            //读取方法数据
            MethodData data = ReadAction.compute(() -> {
                PsiMethod psiMethod = spp.getElement();
                if (psiMethod == null) {
                    return null;
                }

                //获取旧方法、新方法、旧注释
                //TODO
                String oldMethod = """
                            public int sub(int a, int b) {
                                return a - b;
                            }\
                        """;
                String newMethod = """
                            public int add(int a, int b) {
                                return a + b;
                            }\
                        """;
                String oldComment = psiMethod.getDocComment() != null ? psiMethod.getDocComment().getText() : "";
                log.info("Old Method:\n{}\nOld Comment:\n{}\nNew Method:\n{}", oldMethod, oldComment, newMethod);
                return new MethodData(oldMethod, oldComment, newMethod);
            });

            if (data == null) {
                return;
            }

            MethodOptions options = new MethodOptions(CommentGeneratorClient.getSelectedModel());

            //调用注释生成客户端
            String generated = CommentGeneratorClient.generateComment(data, options);
            if (generated == null || generated.isBlank()) {
                return;
            }

            //将生成的注释写回到PsiMethod
            WriteCommandAction.runWriteCommandAction(project, () -> {
                PsiMethod psiMethod = spp.getElement();
                if (psiMethod == null) {
                    return;
                }

                String processed = CommentProcessUtil.processComment(generated, psiMethod);
                log.info("生成的注释:\n{}", processed);

                PsiElementFactory factory = JavaPsiFacade.getElementFactory(project);
                PsiDocComment newComment = factory.createDocCommentFromText(processed);
                PsiDocComment oldComment = psiMethod.getDocComment();
                if (oldComment != null && oldComment.isValid()) {
                    oldComment.replace(newComment);
                } else {
                    psiMethod.addBefore(newComment, psiMethod.getFirstChild());
                }
            });
        });
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        Presentation presentation = e.getPresentation();
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        PsiFile psiFile = e.getData(CommonDataKeys.PSI_FILE);

        boolean visible = false;
        if (psiFile != null) {
            PsiElement element = null;
            if (editor != null) {
                int offset = editor.getCaretModel().getOffset();
                element = psiFile.findElementAt(offset);
            }
            if (element == null) {
                element = e.getData(CommonDataKeys.PSI_ELEMENT);
            }
            PsiMethod method = PsiTreeUtil.getParentOfType(element, PsiMethod.class);
            visible = method != null;
        }

        presentation.setEnabledAndVisible(visible);
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }
}
