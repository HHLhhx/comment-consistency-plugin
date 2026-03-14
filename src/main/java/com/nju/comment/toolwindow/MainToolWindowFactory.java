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

import java.awt.*;
import javax.swing.*;

import org.jetbrains.annotations.NotNull;


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

                    registerUiCallbacks(service, root, project);

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

    private static void registerUiCallbacks(PluginProjectService service, JPanel root, Project project) {
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

        // 注册配置状态变更回调，模型/RAG/自动更新变更后刷新当前界面以反映最新值
        service.setOnGlobalSettingsChangedCallback(() -> {
            if (!AuthManager.isLoggedIn()) return;
            applySettingsToCurrentView(root, project);
        });
    }

    /**
     * 登录面板
     */
    static void showLogin(JPanel root) {
        disposeAndClear(root);
        root.add(new LoginPanel(), BorderLayout.CENTER);
        root.revalidate();
        root.repaint();
    }

    /**
     * 功能主面板
     */
    static void showMain(JPanel root, Project project) {
        disposeAndClear(root);
        root.add(new MainPanel(project,
                () -> showSettings(root, project)
        ), BorderLayout.CENTER);
        root.revalidate();
        root.repaint();
    }

    /**
     * 设置面板
     */
    static void showSettings(JPanel root, Project project) {
        disposeAndClear(root);
        root.add(new SettingsPanel(project,
                () -> showMain(root, project)
        ), BorderLayout.CENTER);
        root.revalidate();
        root.repaint();
    }

    /**
     * 将全局配置应用到当前页面，设置页优先原位刷新以避免闪烁。
     */
    private static void applySettingsToCurrentView(JPanel root, Project project) {
        if (root.getComponentCount() == 0) return;
        Component current = root.getComponent(0);
        if (current instanceof MainPanel) {
            showMain(root, project);
        } else if (current instanceof SettingsPanel) {
            ((SettingsPanel) current).onGlobalSettingsChanged();
        }
    }

    /**
     * 清理当前面板（释放定时器等资源）
     */
    private static void disposeAndClear(JPanel root) {
        for (Component c : root.getComponents()) {
            if (c instanceof Disposable d) {
                d.dispose();
            }
        }
        root.removeAll();
    }
}
