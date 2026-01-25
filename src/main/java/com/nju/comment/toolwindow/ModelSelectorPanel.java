package com.nju.comment.toolwindow;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.nju.comment.client.global.CommentGeneratorClient;
import com.nju.comment.constant.Constant;
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
    private final JToggleButton autoUpdateBtn;

    private final MethodHistoryManager methodHistoryManager =
            new MethodHistoryManager(MethodHistoryRepositoryImpl.getInstance());

    private ScheduledExecutorService autoUpdateScheduler;
    private ScheduledFuture<?> autoUpdateFuture;

    public ModelSelectorPanel(Project project) {
        root = new JPanel(new BorderLayout());
        comboBoxModel = new DefaultComboBoxModel<>();
        modelCombo = new ComboBox<>(comboBoxModel);

        JButton refreshBtn = new JButton("Refresh");
        JButton updateAllMethodsBtn = new JButton("Update All Methods");
        JButton checkMethodRecordsBtn = new JButton("Print Records");
        autoUpdateBtn = new JToggleButton("Auto Update: OFF");

        JPanel row1 = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JPanel comboHolder = new JPanel(new FlowLayout(FlowLayout.LEFT));
        modelCombo.setPreferredSize(new Dimension(250, 42));
        comboHolder.add(modelCombo);
        row1.add(refreshBtn);
        row1.add(comboHolder);
        row1.add(autoUpdateBtn);

        JPanel row2 = new JPanel(new FlowLayout(FlowLayout.LEFT));
        row2.add(updateAllMethodsBtn);
        row2.add(checkMethodRecordsBtn);

        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.add(row1);
        top.add(row2);

        root.add(top, BorderLayout.NORTH);

        refreshBtn.addActionListener(e -> reLoadModels());
        modelCombo.addActionListener(e -> selectModel());
        updateAllMethodsBtn.addActionListener(e -> updateAllMethods(project));
        checkMethodRecordsBtn.addActionListener(e -> methodHistoryManager.printAllMethodRecords());
        autoUpdateBtn.addActionListener(e -> autoUpdate(project));

        loadModels();
    }

    private void autoUpdate(Project project) {
        if (autoUpdateBtn.isSelected()) {
            autoUpdateBtn.setText("Auto Update: ON");
            if (autoUpdateScheduler == null || autoUpdateScheduler.isShutdown()) {
                autoUpdateScheduler = Executors.newSingleThreadScheduledExecutor();
            }
            autoUpdateFuture = autoUpdateScheduler.scheduleWithFixedDelay(() -> {
                PluginProjectService service = project.getService(PluginProjectService.class);
                if (service != null) {
                    service.refreshAllMethodHistories();
                }
            }, Constant.AUTO_UPDATE_INITIAL_DELAY_MS, Constant.AUTO_UPDATE_DELAY_MS, TimeUnit.MILLISECONDS);
        } else {
            autoUpdateBtn.setText("Auto Update: OFF");
            if (autoUpdateFuture != null) {
                autoUpdateFuture.cancel(true);
                autoUpdateFuture = null;
            }
            if (autoUpdateScheduler != null) {
                autoUpdateScheduler.shutdown();
                autoUpdateScheduler = null;
            }
        }
    }

    private static void updateAllMethods(Project project) {
        PluginProjectService service = project.getService(PluginProjectService.class);
        service.refreshAllMethodHistories();
    }

    private void selectModel() {
        String selectedModel = (String) modelCombo.getSelectedItem();
        if (selectedModel != null) {
            CommentGeneratorClient.setSelectedModel(selectedModel);
        }
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
        List<String> models = CommentGeneratorClient.getModelsList();
        ApplicationManager.getApplication().invokeLater(() -> {
            comboBoxModel.removeAllElements();
            models.forEach(comboBoxModel::addElement);
            if (!models.isEmpty()) {
                comboBoxModel.setSelectedItem(models.getFirst());
                CommentGeneratorClient.setSelectedModel(models.getFirst());
            }
        });
    }
}
