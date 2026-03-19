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
import com.nju.comment.service.AuthManager;
import com.nju.comment.pojo.MethodStatus;
import com.nju.comment.service.PluginProjectService;
import com.nju.comment.util.MethodValidationUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;

/**
 * 在发生变更的方法旁边显示 Gutter 图标。
 */
public class CommentGutterIconProvider implements LineMarkerProvider {

    private static final Icon ICON = IconLoader.getIcon("/icons/comment.png", CommentGutterIconProvider.class);

    /**
     * 不同状态对应的 Gutter 提示文案
     */
    private record GutterText(String tooltip, String message, String confirmText) {
    }

    private static @Nullable GutterText resolveText(MethodStatus status) {
        if (status == null) return null;
        return switch (status) {
            case COMMENT_CHANGED -> new GutterText(
                    "检测到注释变更，点击更新",
                    "检测到该方法的注释与历史版本不一致，是否确定以当前注释为准？",
                    "确定");
            case METHOD_CHANGED -> new GutterText(
                    "检测到方法体变更，点击更新注释",
                    "检测到该方法的实现发生变化，是否更新注释？",
                    "更新");
            case NEW_METHOD_WITHOUT_COMMENT -> new GutterText(
                    "检测到新方法且缺少注释，点击生成注释",
                    "检测到新方法且缺少注释，是否为该方法生成注释？",
                    "生成");
            case NEW_METHOD_WITH_COMMENT -> new GutterText(
                    "检测到新方法，点击更新",
                    "检测到新方法，是否确定将该方法加入注释一致性维护管理？",
                    "确定");
            default -> null;
        };
    }

    @Override
    public @Nullable LineMarkerInfo<?> getLineMarkerInfo(@NotNull PsiElement element) {
        if (!AuthManager.isLoggedIn()) return null;

        if (!(element instanceof PsiIdentifier)) return null;
        if (!(element.getParent() instanceof PsiMethod method)) return null;

        if (!MethodValidationUtil.isValid(method)) {
            return null;
        }

        Project project = method.getProject();
        PluginProjectService service = project.getService(PluginProjectService.class);

        // 初次检查：决定是否显示图标
        MethodStatus status = service.preCheckChange(method);
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

    /**
     * 每次 hover 时实时获取最新状态并返回对应 tooltip
     */
    private String computeTooltip(PsiElement element) {
        if (!(element.getParent() instanceof PsiMethod method) || !method.isValid()) return "";
        MethodStatus status = method.getProject().getService(PluginProjectService.class).preCheckChange(method);
        GutterText text = resolveText(status);
        return text != null ? text.tooltip() : "";
    }

    /**
     * 点击时实时获取最新状态，展示对应弹窗并执行操作
     */
    private void handleClick(PsiElement element, Project project) {
        if (!(element.getParent() instanceof PsiMethod method) || !method.isValid()) return;

        PluginProjectService service = project.getService(PluginProjectService.class);
        MethodStatus status = service.preCheckChange(method);
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
