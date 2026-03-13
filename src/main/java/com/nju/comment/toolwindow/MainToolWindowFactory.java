package com.nju.comment.toolwindow;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import com.nju.comment.service.AuthManager;
import com.nju.comment.service.PluginProjectService;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;

/**
 * ToolWindow 工厂，管理三屏切换：登录 ↔ 功能主面板 ↔ 设置。
 */
public class MainToolWindowFactory implements ToolWindowFactory {

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        PluginProjectService service = project.getService(PluginProjectService.class);

        service.getInitializationFuture().thenRun(() ->
                ApplicationManager.getApplication().invokeLater(() -> {
                    JPanel root = new JPanel(new BorderLayout());

                    // 注册强制登出回调，用于服务端凭证失效时自动切回登录界面
                    service.setOnForceLogoutCallback(() -> showLogin(root));

                    // 注册认证状态变更回调，登录成功/登出后自动切换界面
                    service.setOnAuthStateChangedCallback(() -> {
                        if (AuthManager.isLoggedIn()) {
                            showMain(root, project);
                            DumbService.getInstance(project).runWhenSmart(service::refreshAllMethodHistories);
                        } else {
                            showLogin(root);
                        }
                    });

                    // 初始界面根据认证状态决定
                    if (AuthManager.isLoggedIn()) {
                        showMain(root, project);
                        DumbService.getInstance(project).runWhenSmart(service::refreshAllMethodHistories);
                    } else {
                        showLogin(root);
                    }

                    Content content = ContentFactory.getInstance().createContent(root, "", false);
                    toolWindow.getContentManager().addContent(content);
                }));
    }

    /** 登录面板 */
    static void showLogin(JPanel root) {
        disposeAndClear(root);
        root.add(new LoginPanel(), BorderLayout.CENTER);
        root.revalidate();
        root.repaint();
    }

    /** 功能主面板 */
    static void showMain(JPanel root, Project project) {
        disposeAndClear(root);
        root.add(new MainPanel(project,
                () -> showSettings(root, project)
        ), BorderLayout.CENTER);
        root.revalidate();
        root.repaint();
    }

    /** 设置面板 */
    static void showSettings(JPanel root, Project project) {
        disposeAndClear(root);
        root.add(new SettingsPanel(project,
                () -> showMain(root, project)
        ), BorderLayout.CENTER);
        root.revalidate();
        root.repaint();
    }

    /** 清理当前面板（释放定时器等资源） */
    private static void disposeAndClear(JPanel root) {
        for (Component c : root.getComponents()) {
            if (c instanceof Disposable d) {
                d.dispose();
            }
        }
        root.removeAll();
    }
}
