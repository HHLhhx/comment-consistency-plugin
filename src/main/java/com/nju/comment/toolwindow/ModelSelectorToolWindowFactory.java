package com.nju.comment.toolwindow;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import com.nju.comment.service.PluginProjectService;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;
import java.util.concurrent.CompletableFuture;

public class ModelSelectorToolWindowFactory implements ToolWindowFactory {

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        PluginProjectService service = project.getService(PluginProjectService.class);
        CompletableFuture<Void> initFuture = service.getInitializationFuture();

        initFuture.thenRun(() -> ApplicationManager.getApplication().invokeLater(() -> {
            ModelSelectorPanel selectorPanel = new ModelSelectorPanel(project);
            HistoryCardsPanel historyCardsPanel = new HistoryCardsPanel(project);

            JPanel root = new JPanel(new BorderLayout());
            root.add(selectorPanel.getRoot(), BorderLayout.NORTH);
            root.add(historyCardsPanel.getRoot(), BorderLayout.CENTER);

            ContentFactory contentFactory = ContentFactory.getInstance();
            Content content = contentFactory.createContent(root, "", false);
            toolWindow.getContentManager().addContent(content);

            DumbService.getInstance(project).runWhenSmart(service::refreshAllMethodHistories);
        }));
    }
}
