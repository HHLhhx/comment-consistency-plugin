package com.nju.comment.toolwindow;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;

public class ModelSelectorToolWindowFactory implements ToolWindowFactory {

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        ModelSelectorPanel selectorPanel = new ModelSelectorPanel(project);
        HistoryCardsPanel historyCardsPanel = new HistoryCardsPanel(project);

        JPanel root = new JPanel(new BorderLayout());
        root.add(selectorPanel.getRoot(), BorderLayout.NORTH);
        root.add(historyCardsPanel.getRoot(), BorderLayout.CENTER);

        ContentFactory contentFactory = ContentFactory.getInstance();
        Content content = contentFactory.createContent(root, "", false);
        toolWindow.getContentManager().addContent(content);
    }
}
