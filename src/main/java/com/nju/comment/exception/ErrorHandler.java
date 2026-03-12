package com.nju.comment.exception;

import com.intellij.notification.Notification;
import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.nju.comment.service.AuthManager;
import com.nju.comment.service.PluginProjectService;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;

/**
 * 插件端统一错误处理器。
 * <p>
 * 职责：
 * <ul>
 *   <li>根据 {@link ErrorCode} 按级别记录日志</li>
 *   <li>需要用户感知的错误通过 IDEA Notification 弹出提示</li>
 *   <li>可重试的错误（如 LLM_TIMEOUT）附带重试操作按钮</li>
 * </ul>
 * <p>
 * 扩展方式：在 {@link #handle} 的 switch 分支中添加新的 ErrorCode 处理即可。
 */
@Slf4j
public final class ErrorHandler {

    private static final String NOTIFICATION_GROUP_ID = "Comment Consistency";

    private ErrorHandler() {
    }

    /**
     * 处理后端异常。记录日志并在必要时通知用户。
     *
     * @param ex          后端异常
     * @param retryAction 可重试时的重试动作，为 null 则不提供重试
     */
    public static void handle(BackendException ex, Runnable retryAction) {
        ErrorCode code = ex.getErrorCode();
        String msg = ex.getMessage();

        // 1. 按级别记录日志
        switch (code.getLevel()) {
            case WARN -> log.warn("[{}] {}", code.name(), msg);
            case ERROR -> log.error("[{}] {}", code.name(), msg);
        }

        // 2. 按错误码决定用户通知策略
        switch (code) {
            case LLM_TIMEOUT -> notifyWithRetry(
                    "LLM 调用超时",
                    "LLM 调用超时，请稍后重试。",
                    retryAction);

            case COMMENT_SERVICE_ERROR -> notify(
                    "注释服务异常",
                    "注释服务处理异常: " + msg,
                    NotificationType.ERROR);

            case LLM_MODEL_FETCH_ERROR -> notify(
                    "模型列表获取失败",
                    "无法获取可用模型列表，请稍后点击 Refresh 重试。",
                    NotificationType.WARNING);

            case SYSTEM_ERROR -> notify(
                    "系统错误",
                    "后端系统异常: " + msg,
                    NotificationType.ERROR);

            case TIMEOUT_ERROR -> notify(
                    "请求超时",
                    "请求超时，请稍后重试。",
                    NotificationType.WARNING);

            case PARAMETER_ERROR -> log.warn("参数错误（不通知用户）: {}", msg);

            case AUTH_TOKEN_EXPIRED, AUTH_TOKEN_INVALID, AUTH_TOKEN_BLACKLISTED, AUTH_NOT_LOGGED_IN -> {
                for (Project p : ProjectManager.getInstance().getOpenProjects()) {
                    if (p.isDisposed()) continue;
                    p.getService(PluginProjectService.class).forceLogout();
                }
                notify("登录已失效",
                        "登录凭证已过期或无效，请重新登录。",
                        NotificationType.WARNING);
            }

            case AUTH_LOGIN_FAILED -> notify(
                    "登录失败",
                    "用户名或密码错误，请重试。",
                    NotificationType.WARNING);

            case AUTH_USERNAME_EXISTS -> notify(
                    "注册失败",
                    "用户名已存在，请换一个用户名。",
                    NotificationType.WARNING);

            case AUTH_PHONE_EXISTS -> notify(
                    "注册失败",
                    "手机号已被注册，请更换手机号。",
                    NotificationType.WARNING);

            case LLM_API_KEY_NOT_SET -> notify(
                    "API Key 未配置",
                    "请先在后端配置 API Key 后再使用。",
                    NotificationType.WARNING);

            case LLM_API_KEY_INVALID -> notify(
                    "API Key 无效",
                    "当前配置的 API Key 无效，请更新。",
                    NotificationType.ERROR);

            default -> log.error("未处理的后端错误 [code={}]: {}", code.getCode(), msg);
        }
    }

    /**
     * 无重试的便捷入口
     */
    public static void handle(BackendException ex) {
        handle(ex, null);
    }

    // ========================== 通知工具方法 ==========================

    private static void notify(String title, String content, NotificationType type) {
        ApplicationManager.getApplication().invokeLater(() -> {
            Project project = getOpenProject();
            NotificationGroupManager.getInstance()
                    .getNotificationGroup(NOTIFICATION_GROUP_ID)
                    .createNotification(title, content, type)
                    .notify(project);
        });
    }

    private static void notifyWithRetry(String title, String content, Runnable retryAction) {
        ApplicationManager.getApplication().invokeLater(() -> {
            Project project = getOpenProject();
            var notification = NotificationGroupManager.getInstance()
                    .getNotificationGroup(NOTIFICATION_GROUP_ID)
                    .createNotification(title, content, NotificationType.WARNING);

            if (retryAction != null) {
                notification.addAction(new NotificationAction("重试") {
                    @Override
                    public void actionPerformed(
                            @NotNull AnActionEvent e,
                            @NotNull Notification n) {
                        n.expire();
                        ApplicationManager.getApplication().executeOnPooledThread(retryAction);
                    }
                });
            }

            notification.notify(project);
        });
    }

    private static Project getOpenProject() {
        Project[] projects = ProjectManager.getInstance().getOpenProjects();
        return projects.length > 0 ? projects[0] : null;
    }
}
