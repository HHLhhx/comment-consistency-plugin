package com.nju.comment.extension;

import com.intellij.codeInsight.daemon.LineMarkerInfo;
import com.intellij.codeInsight.daemon.LineMarkerProvider;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.markup.GutterIconRenderer;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.ComponentPopupBuilder;
import com.intellij.openapi.ui.popup.JBPopup;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.util.IconLoader;
import com.intellij.psi.*;
import com.intellij.psi.javadoc.PsiDocComment;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.ui.awt.RelativePoint;
import com.intellij.ui.components.JBLabel;
import com.nju.comment.client.global.CommentGeneratorClient;
import com.nju.comment.dto.MethodContext;
import com.nju.comment.dto.GenerateOptions;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseEvent;

public class MethodCommentLineMarkerProvider implements LineMarkerProvider {

    @Override
    public @Nullable LineMarkerInfo<?> getLineMarkerInfo(@NotNull PsiElement psiElement) {
        PsiMethod method = PsiTreeUtil.getParentOfType(psiElement, PsiMethod.class, false);
        if (method == null) {
            return null;
        }

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
                (e, elt) -> onIconClick(e, method),
                GutterIconRenderer.Alignment.RIGHT
        );
    }

    private void onIconClick(MouseEvent e, PsiMethod method) {
        Project project = method.getProject();

        SmartPointerManager spm = SmartPointerManager.getInstance(project);
        SmartPsiElementPointer<PsiMethod> methodPointer = spm.createSmartPsiElementPointer(method);

        JPanel contentPanel = new JPanel(new BorderLayout());

        //注释语言
        JPanel languagePanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        languagePanel.add(new JBLabel("Choose language:"));
        JRadioButton rbChinese = new JRadioButton("Chinese", true);
        JRadioButton rbEnglish = new JRadioButton("English");
        ButtonGroup languageGroup = new ButtonGroup();
        languageGroup.add(rbChinese);
        languageGroup.add(rbEnglish);
        languagePanel.add(rbChinese);
        languagePanel.add(rbEnglish);

        //注释风格
        JPanel stylePanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JCheckBox javadocCb = new JCheckBox("Javadoc", true);
        stylePanel.add(javadocCb);

        JPanel optionsPanel = new JPanel();
        optionsPanel.setLayout(new BoxLayout(optionsPanel, BoxLayout.Y_AXIS));
        optionsPanel.add(languagePanel);
        optionsPanel.add(stylePanel);

        //注释按钮
        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton ok = new JButton("确定");
        JButton cancel = new JButton("取消");
        btnPanel.add(ok);
        btnPanel.add(cancel);

        contentPanel.add(optionsPanel, BorderLayout.NORTH);
        contentPanel.add(btnPanel, BorderLayout.SOUTH);

        ComponentPopupBuilder builder = JBPopupFactory.getInstance().createComponentPopupBuilder(contentPanel, null);
        builder.setTitle("生成/更新注释");
        builder.setResizable(false);
        builder.setMovable(true);
        JBPopup popup = builder.createPopup();

        cancel.addActionListener(a -> popup.cancel());
        ok.addActionListener(a -> {
            popup.cancel();

            ApplicationManager.getApplication().executeOnPooledThread(() -> {
                MethodContext data = ReadAction.compute(() -> {
                    PsiMethod psiMethod = methodPointer.getElement();
                    if (psiMethod == null) {
                        return null;
                    }

                    String signature = psiMethod.getName() + method.getParameterList().getText();
                    String body = psiMethod.getBody() != null
                            ? psiMethod.getBody().getText() : "";
                    String existingComment = psiMethod.getDocComment() != null
                            ? psiMethod.getDocComment().getText() : "";

                    return new MethodContext(signature, body, existingComment);
                });

                if (data == null) {
                    return;
                }

                GenerateOptions options = new GenerateOptions(CommentGeneratorClient.getSelectedModel());
                String generated = CommentGeneratorClient.generateComment(data, options);

                if (generated == null || generated.isBlank()) {
                    return;
                }

                WriteCommandAction.runWriteCommandAction(project, () -> {
                    PsiMethod psiMethod = methodPointer.getElement();
                    if (psiMethod == null) {
                        return;
                    }

                    String processed = processComment(generated, psiMethod);

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
        });

        popup.show(new RelativePoint(e));
    }

    //TODO: 更加健壮的处理
    private String processComment(String generated, PsiMethod psiMethod) {
        String sep = "\n";
        PsiFile file = psiMethod.getContainingFile();
        if (file != null) {
            String fileText = file.getText();
            if (fileText.contains("\r\n")) {
                sep = "\r\n";
            } else if (fileText.contains("\r")) {
                sep = "\r";
            }
        }

        String processed = generated == null ? "" : generated.trim();
        // 统一所有已有分隔符为目标分隔符
        processed = processed.replace("\r\n", sep).replace("\r", sep);

        int idx = processed.indexOf("/**");
        if (idx >= 0) {
            processed = processed.substring(idx).trim();
            if (processed.contains("*/")) {
                processed = processed.substring(0, processed.indexOf("*/") + 2).trim();
            } else {
                // 确保用统一分隔符并正确闭合
                if (!processed.endsWith(sep)) {
                    processed += sep;
                }
                processed += " */";
            }
        } else {
            String body = processed.replaceAll("\r\n", sep).replaceAll("\r", sep);
            String[] lines = body.split("\n", -1); // 使用 LF 分割再在下面使用 sep 重建
            StringBuilder sb = new StringBuilder("/**").append(sep);
            for (String line : lines) {
                sb.append(" * ").append(line).append(sep);
            }
            sb.append(" */");
            processed = sb.toString();
        }

        return processed;
    }
}
