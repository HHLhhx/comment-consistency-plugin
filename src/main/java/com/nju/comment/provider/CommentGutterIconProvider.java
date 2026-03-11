package com.nju.comment.provider;

import com.intellij.codeInsight.daemon.LineMarkerInfo;
import com.intellij.codeInsight.daemon.LineMarkerProvider;
import com.intellij.openapi.editor.markup.GutterIconRenderer;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.IconLoader;
import com.intellij.psi.*;
import com.nju.comment.dto.MethodRecord;
import com.nju.comment.dto.MethodStatus;
import com.nju.comment.history.MethodHistoryRepositoryImpl;
import com.nju.comment.service.PluginProjectService;
import com.nju.comment.util.MethodRecordUtil;
import com.nju.comment.util.TextProcessUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.util.Objects;

/**
 * 在需要手动触发注释更新/生成的方法旁边显示 Gutter 图标。
 * <ul>
 *   <li>NEW_METHOD_WITHOUT_COMMENT：无论自动更新开关，始终显示（需手动生成）</li>
 *   <li>METHOD_CHANGED / 方法体漂移：仅在自动更新关闭时显示（需手动更新）</li>
 * </ul>
 */
public class CommentGutterIconProvider implements LineMarkerProvider {

    private static final Icon ICON = IconLoader.getIcon("/icons/comment.png", CommentGutterIconProvider.class);

    @Override
    public @Nullable LineMarkerInfo<?> getLineMarkerInfo(@NotNull PsiElement element) {
        if (!(element instanceof PsiIdentifier)) return null;
        PsiElement parent = element.getParent();
        if (!(parent instanceof PsiMethod method)) return null;

        Project project = method.getProject();
        PluginProjectService service = project.getService(PluginProjectService.class);

        String key = MethodRecordUtil.buildMethodKey(method);
        if (key.isBlank()) return null;

        MethodRecord record = MethodHistoryRepositoryImpl.getInstance().findByKey(key);
        if (record == null) return null;

        MethodStatus status = record.getStatus();
        boolean needsAttention = false;
        boolean isGenerate = false;

        if (MethodStatus.NEW_METHOD_WITHOUT_COMMENT.equals(status)) {
            // 始终显示：NEW_METHOD_WITHOUT_COMMENT 必须手动生成
            needsAttention = true;
            isGenerate = true;
        } else if (!service.isAutoUpdateEnabled()) {
            // 自动更新关闭时：检测需要手动更新的方法
            if (MethodStatus.METHOD_CHANGED.equals(status)) {
                needsAttention = true;
            } else if (MethodStatus.UNCHANGED.equals(status)
                    || MethodStatus.NEW_METHOD_WITH_COMMENT.equals(status)
                    || MethodStatus.COMMENT_CHANGED.equals(status)) {
                // 漂移检测：当前方法体与记录中的暂存方法体不一致
                String currentMethod = extractMethodBody(method);
                String processed = TextProcessUtil.processMethod(currentMethod);
                if (!Objects.equals(processed, record.getStagedMethod())) {
                    needsAttention = true;
                }
            }
        }

        if (!needsAttention) return null;

        final boolean generateMode = isGenerate;
        String tooltip = generateMode ? "生成方法注释" : "更新方法注释";

        return new LineMarkerInfo<>(
                element,
                element.getTextRange(),
                ICON,
                e -> tooltip,
                (mouseEvent, el) -> {
                    PsiMethod m = (PsiMethod) el.getParent();
                    String title = "注释操作";
                    String message = generateMode ? "是否为该方法生成注释？" : "是否更新该方法的注释？";
                    String yesText = generateMode ? "生成" : "更新";

                    int result = Messages.showYesNoDialog(project, message, title, yesText, "取消",
                            Messages.getQuestionIcon());
                    if (result == Messages.YES) {
                        PluginProjectService svc = project.getService(PluginProjectService.class);
                        if (generateMode) {
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

    /**
     * 提取方法体文本（去除前导注释和空白）。
     */
    private static String extractMethodBody(PsiMethod method) {
        PsiElement child = method.getFirstChild();
        while (child instanceof PsiComment || child instanceof PsiWhiteSpace) {
            child = child.getNextSibling();
        }
        if (child == null) return "";
        int start = child.getTextRange().getStartOffset();
        int end = method.getTextRange().getEndOffset();
        return method.getContainingFile().getText().substring(start, end).trim();
    }
}
