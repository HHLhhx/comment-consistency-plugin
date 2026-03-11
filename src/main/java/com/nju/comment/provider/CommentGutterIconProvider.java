package com.nju.comment.provider;

import com.intellij.codeInsight.daemon.LineMarkerInfo;
import com.intellij.codeInsight.daemon.LineMarkerProvider;
import com.intellij.openapi.editor.markup.GutterIconRenderer;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.IconLoader;
import com.intellij.psi.*;
import com.nju.comment.history.MethodHistoryManager;
import com.nju.comment.pojo.MethodStatus;
import com.nju.comment.history.MethodHistoryRepositoryImpl;
import com.nju.comment.service.PluginProjectService;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;

/**
 * 在需要手动触发注释更新/生成的方法旁边显示 Gutter 图标。
 * <ul>
 *   <li>NEW_METHOD_WITHOUT_COMMENT：无论自动更新开关，始终显示（需手动生成）</li>
 *   <li>METHOD_CHANGED / 方法体漂移：仅在自动更新关闭时显示（需手动更新）</li>
 * </ul>
 */
public class CommentGutterIconProvider implements LineMarkerProvider {

    private static final Icon ICON = IconLoader.getIcon("/icons/comment.png", CommentGutterIconProvider.class);

    private final MethodHistoryManager historyManager =
            new MethodHistoryManager(MethodHistoryRepositoryImpl.getInstance());

    @Override
    public @Nullable LineMarkerInfo<?> getLineMarkerInfo(@NotNull PsiElement element) {
        if (!(element instanceof PsiIdentifier)) return null;
        PsiElement parent = element.getParent();
        if (!(parent instanceof PsiMethod method)) return null;

        Project project = method.getProject();
        PluginProjectService service = project.getService(PluginProjectService.class);

        MethodStatus status = service.preCheckChange(method);

        if (MethodStatus.UNCHANGED.equals(status)) return null;

        String tooltip;
        String message;
        String yesText;

        if (MethodStatus.COMMENT_CHANGED.equals(status)) {
            tooltip = "检测到注释变更，点击更新";
            message = "检测到该方法的注释与历史版本不一致，是否确定以当前注释为准？";
            yesText = "确定";
        } else if (MethodStatus.METHOD_CHANGED.equals(status)) {
            tooltip = "检测到方法体变更，点击更新注释";
            message = "检测到该方法的实现发生变化，是否更新注释？";
            yesText = "更新";
        } else if (MethodStatus.NEW_METHOD_WITHOUT_COMMENT.equals(status)) {
            tooltip = "检测到新方法且缺少注释，点击生成注释";
            message = "检测到新方法且缺少注释，是否为该方法生成注释？";
            yesText = "生成";
        } else if (MethodStatus.NEW_METHOD_WITH_COMMENT.equals(status)) {
            tooltip = "检测到新方法，点击更新";
            message = "检测到新方法，是否确定将该方法加入注释一致性维护管理？";
            yesText = "确定";
        } else {
            tooltip = "";
            message = "";
            yesText = "";
        }

        return new LineMarkerInfo<>(
                element,
                element.getTextRange(),
                ICON,
                e -> tooltip,
                (mouseEvent, el) -> {
                    PsiMethod m = (PsiMethod) el.getParent();

                    int result = Messages.showYesNoDialog(project, message, "注释一致性维护管理", yesText, "取消",
                            Messages.getQuestionIcon());
                    if (result == Messages.YES) {
                        PluginProjectService svc = project.getService(PluginProjectService.class);
                        if (MethodStatus.NEW_METHOD_WITHOUT_COMMENT.equals(status)) {
                            svc.generateComment(m);
                        } else {
                            svc.refreshMethodHistory(m);
                        }
                    }
                },
                GutterIconRenderer.Alignment.LEFT,
                () -> tooltip
        );
    }
}
