package com.nju.comment.toolwindow;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
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

public class MainToolWindowFactory implements ToolWindowFactory {

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        PluginProjectService service = project.getService(PluginProjectService.class);

        service.getInitializationFuture().thenRun(() ->
                ApplicationManager.getApplication().invokeLater(() -> {
                    JPanel root = new JPanel(new BorderLayout());

                    registerUiCallbacks(service, root, project);

                    if (AuthManager.isLoggedIn()) {
                        showMain(root, project);
                    } else {
                        showLogin(root);
                    }

                    Content content = ContentFactory.getInstance().createContent(root, "", false);
                    toolWindow.getContentManager().addContent(content);
                }));
    }

    private static void registerUiCallbacks(PluginProjectService service, JPanel root, Project project) {
        service.setOnForceLogoutCallback(() -> showLogin(root));

        service.setOnAuthStateChangedCallback(() -> {
            if (AuthManager.isLoggedIn()) {
                showMain(root, project);
            } else {
                showLogin(root);
            }
        });

        service.setOnGlobalSettingsChangedCallback(() -> {
            if (!AuthManager.isLoggedIn()) return;
            applySettingsToCurrentView(root);
        });
    }

    static void showLogin(JPanel root) {
        disposeAndClear(root);
        root.add(new LoginPanel(), BorderLayout.CENTER);
        root.revalidate();
        root.repaint();
    }

    static void showMain(JPanel root, Project project) {
        disposeAndClear(root);
        root.add(new MainPanel(project,
                () -> showSettings(root, project)
        ), BorderLayout.CENTER);
        root.revalidate();
        root.repaint();
    }

    static void showSettings(JPanel root, Project project) {
        disposeAndClear(root);
        root.add(new SettingsPanel(project,
                () -> showMain(root, project)
        ), BorderLayout.CENTER);
        root.revalidate();
        root.repaint();
    }

    private static void applySettingsToCurrentView(JPanel root) {
        if (root.getComponentCount() == 0) return;
        Component current = root.getComponent(0);
        if (current instanceof MainPanel mainPanel) {
            mainPanel.onGlobalSettingsChanged();
        } else if (current instanceof SettingsPanel settingsPanel) {
            settingsPanel.onGlobalSettingsChanged();
        }
    }

    private static void disposeAndClear(JPanel root) {
        for (Component c : root.getComponents()) {
            if (c instanceof Disposable d) {
                d.dispose();
            }
        }
        root.removeAll();
    }
}
