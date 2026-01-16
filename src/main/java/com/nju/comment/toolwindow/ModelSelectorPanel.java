package com.nju.comment.toolwindow;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.ui.ComboBox;
import com.nju.comment.client.global.CommentGeneratorClient;
import lombok.Getter;

import javax.swing.*;
import java.awt.*;
import java.util.List;

public class ModelSelectorPanel {
    @Getter
    private final JPanel root;
    private final DefaultComboBoxModel<String> comboBoxModel;
    private final ComboBox<String> modelCombo;

    public ModelSelectorPanel() {
        root = new JPanel(new BorderLayout());
        comboBoxModel = new DefaultComboBoxModel<>();
        modelCombo = new ComboBox<>(comboBoxModel);

        JButton refreshBtn = new JButton("Refresh");

        JPanel comboHolder = new JPanel(new FlowLayout(FlowLayout.LEFT));
        modelCombo.setPreferredSize(new Dimension(250, 42));
        comboHolder.add(modelCombo);

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT));
        top.add(refreshBtn);
        top.add(comboHolder);

        root.add(top, BorderLayout.NORTH);

        refreshBtn.addActionListener(e -> reLoadModels());
        modelCombo.addActionListener(e -> {
            String selectedModel = (String) modelCombo.getSelectedItem();
            if (selectedModel != null) {
                CommentGeneratorClient.setSelectedModel(selectedModel);
            }
        });

        loadModels();
    }

    private void reLoadModels() {
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            List<String> models = CommentGeneratorClient.getAvailableModels();
            ApplicationManager.getApplication().invokeLater(() -> {
                comboBoxModel.removeAllElements();
                models.forEach(comboBoxModel::addElement);
                String sel = CommentGeneratorClient.getSelectedModel();
                if (sel != null && !sel.isEmpty() && models.contains(sel)) {
                    comboBoxModel.setSelectedItem(sel);
                } else if (!models.isEmpty()) {
                    comboBoxModel.setSelectedItem(models.getFirst());
                    CommentGeneratorClient.setSelectedModel(models.getFirst());
                }
            });
        });
    }

    private void loadModels() {
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            List<String> models = CommentGeneratorClient.getModelsList();
            ApplicationManager.getApplication().invokeLater(() -> {
                comboBoxModel.removeAllElements();
                models.forEach(comboBoxModel::addElement);
                if (!models.isEmpty()) {
                    comboBoxModel.setSelectedItem(models.getFirst());
                    CommentGeneratorClient.setSelectedModel(models.getFirst());
                }
            });
        });
    }
}
