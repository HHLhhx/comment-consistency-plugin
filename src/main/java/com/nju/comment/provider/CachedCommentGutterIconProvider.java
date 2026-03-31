package com.nju.comment.provider;

import com.intellij.codeInsight.daemon.LineMarkerInfo;
import com.intellij.codeInsight.daemon.LineMarkerProvider;
import com.intellij.openapi.editor.markup.GutterIconRenderer;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.IconLoader;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiIdentifier;
import com.intellij.psi.PsiMethod;
import com.nju.comment.pojo.MethodStatus;
import com.nju.comment.service.AuthManager;
import com.nju.comment.service.PluginProjectService;
import com.nju.comment.util.MethodValidationUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;

public class CachedCommentGutterIconProvider implements LineMarkerProvider {

    private static final Icon ICON = IconLoader.getIcon("/icons/comment.png", CachedCommentGutterIconProvider.class);

    private record GutterText(String tooltip, String message, String confirmText) {
    }

    private static @Nullable GutterText resolveText(MethodStatus status) {
        if (status == null) return null;
        return switch (status) {
            case METHOD_CHANGED -> new GutterText(
                    "检测到方法体变更，点击更新注释",
                    "检测到该方法的实现发生变化，是否更新注释？",
                    "更新");
            case NEW_METHOD_WITHOUT_COMMENT -> new GutterText(
                    "检测到新方法且缺少注释，点击生成注释",
                    "检测到新方法且缺少注释，是否为该方法生成注释？",
                    "生成");
            default -> null;
        };
    }

    @Override
    public @Nullable LineMarkerInfo<?> getLineMarkerInfo(@NotNull PsiElement element) {
        if (!AuthManager.isLoggedIn()) return null;
        if (!(element instanceof PsiIdentifier)) return null;
        if (!(element.getParent() instanceof PsiMethod method)) return null;
        if (!MethodValidationUtil.isQuicklyEligible(method)) return null;

        Project project = method.getProject();
        PluginProjectService service = project.getService(PluginProjectService.class);
        MethodStatus status = service.getCachedMethodStatus(method);
        if (resolveText(status) == null) return null;

        return new LineMarkerInfo<>(
                element,
                element.getTextRange(),
                ICON,
                this::computeTooltip,
                (mouseEvent, el) -> handleClick(el, project),
                GutterIconRenderer.Alignment.LEFT,
                () -> "注释一致性维护"
        );
    }

    private String computeTooltip(PsiElement element) {
        if (!(element.getParent() instanceof PsiMethod method) || !method.isValid()) return "";
        MethodStatus status = method.getProject().getService(PluginProjectService.class).getCachedMethodStatus(method);
        GutterText text = resolveText(status);
        return text != null ? text.tooltip() : "";
    }

    private void handleClick(PsiElement element, Project project) {
        if (!(element.getParent() instanceof PsiMethod method) || !method.isValid()) return;
        if (!MethodValidationUtil.isQuicklyEligible(method)) return;

        PluginProjectService service = project.getService(PluginProjectService.class);
        MethodStatus status = service.getCachedMethodStatus(method);
        GutterText text = resolveText(status);
        if (text == null) return;

        int result = Messages.showYesNoDialog(
                project, text.message(), "注释一致性维护管理",
                text.confirmText(), "取消", Messages.getQuestionIcon());
        if (result != Messages.YES) return;

        if (MethodStatus.NEW_METHOD_WITHOUT_COMMENT.equals(status)) {
            service.generateComment(method);
        } else {
            service.refreshMethodHistory(method);
        }
    }
}
