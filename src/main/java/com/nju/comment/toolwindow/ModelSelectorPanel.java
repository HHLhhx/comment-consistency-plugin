package com.nju.comment.toolwindow;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.nju.comment.client.global.CommentGeneratorClient;
import com.nju.comment.history.MethodHistoryManager;
import com.nju.comment.history.MethodHistoryRepositoryImpl;
import com.nju.comment.service.PluginProjectService;
import lombok.Getter;

import javax.swing.*;
import java.awt.*;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class ModelSelectorPanel {
    @Getter
    private final JPanel root;
    private final DefaultComboBoxModel<String> comboBoxModel;
    private final ComboBox<String> modelCombo;

    private final MethodHistoryManager methodHistoryManager = new MethodHistoryManager(MethodHistoryRepositoryImpl.getInstance());

    private ScheduledExecutorService autoScheduler;
    private ScheduledFuture<?> autoFuture;

    public ModelSelectorPanel(Project project) {
        root = new JPanel(new BorderLayout());
        comboBoxModel = new DefaultComboBoxModel<>();
        modelCombo = new ComboBox<>(comboBoxModel);

        JButton refreshBtn = new JButton("Refresh");
        JButton updateAllMethodsBtn = new JButton("Update All Methods");
        JButton checkMethodRecordsBtn = new JButton("Print Records");
        JToggleButton autoToggleBtn = new JToggleButton("Auto Update: OFF");

        JPanel row1 = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JPanel comboHolder = new JPanel(new FlowLayout(FlowLayout.LEFT));
        modelCombo.setPreferredSize(new Dimension(250, 42));
        comboHolder.add(modelCombo);
        row1.add(refreshBtn);
        row1.add(comboHolder);
        row1.add(autoToggleBtn);

        JPanel row2 = new JPanel(new FlowLayout(FlowLayout.LEFT));
        row2.add(updateAllMethodsBtn);
        row2.add(checkMethodRecordsBtn);

        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.add(row1);
        top.add(row2);

        root.add(top, BorderLayout.NORTH);

        refreshBtn.addActionListener(e -> reLoadModels());
        modelCombo.addActionListener(e -> {
            String selectedModel = (String) modelCombo.getSelectedItem();
            if (selectedModel != null) {
                CommentGeneratorClient.setSelectedModel(selectedModel);
            }
        });
        updateAllMethodsBtn.addActionListener(e -> {
            PluginProjectService service = project.getService(PluginProjectService.class);
            service.refreshAllMethodHistories();
        });
        checkMethodRecordsBtn.addActionListener(e -> methodHistoryManager.printAllMethodRecords());
        autoToggleBtn.addActionListener(e -> {
            if (autoToggleBtn.isSelected()) {
                autoToggleBtn.setText("Auto Update: ON");
                if (autoScheduler == null || autoScheduler.isShutdown()) {
                    autoScheduler = Executors.newSingleThreadScheduledExecutor();
                }
                autoFuture = autoScheduler.scheduleWithFixedDelay(() -> {
                    PluginProjectService service = project.getService(PluginProjectService.class);
                    if (service != null) {
                        service.refreshAllMethodHistories();
                    }
                }, 0, 3, TimeUnit.SECONDS);
            } else {
                autoToggleBtn.setText("Auto Update: OFF");
                if (autoFuture != null) {
                    autoFuture.cancel(true);
                    autoFuture = null;
                }
                if (autoScheduler != null) {
                    autoScheduler.shutdown();
                    autoScheduler = null;
                }
            }
        });

        loadModels();
    }

    private void reLoadModels() {
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            List<String> models;
            if (CommentGeneratorClient.getModelsList().isEmpty()) {
                models = CommentGeneratorClient.getAvailableModels();
            } else {
                models = CommentGeneratorClient.getModelsList();
            }
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
