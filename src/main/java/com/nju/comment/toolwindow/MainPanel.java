package com.nju.comment.toolwindow;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;
import com.nju.comment.client.global.AuthManager;
import com.nju.comment.client.global.CommentGeneratorClient;
import com.nju.comment.constant.Constant;
import com.nju.comment.dto.MethodRecord;
import com.nju.comment.dto.MethodStatus;
import com.nju.comment.history.MethodHistoryManager;
import com.nju.comment.history.MethodHistoryRepositoryImpl;
import com.nju.comment.service.PluginProjectService;
import lombok.extern.slf4j.Slf4j;

import javax.swing.*;
import java.awt.*;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * 主功能面板：工具栏（模型选择 / RAG 开关 / 操作按钮）+ 方法注释变更卡片列表。
 */
@Slf4j
public class MainPanel extends JPanel implements Disposable {

    private final Project project;
    private final DefaultComboBoxModel<String> comboBoxModel;
    private final ComboBox<String> modelCombo;
    private final JPanel cardsListPanel;

    private final ScheduledExecutorService uiScheduler = Executors.newSingleThreadScheduledExecutor();
    private Set<String> lastSeenSignatures = null;
    private boolean suppressModelAction = false;

    private final MethodHistoryManager historyManager =
            new MethodHistoryManager(MethodHistoryRepositoryImpl.getInstance());

    public MainPanel(Project project, Runnable onOpenSettings, Runnable onLogout) {
        this.project = project;
        setLayout(new BorderLayout());

        comboBoxModel = new DefaultComboBoxModel<>();
        modelCombo = new ComboBox<>(comboBoxModel);
        modelCombo.setPreferredSize(new Dimension(180, 28));

        // ---- 工具栏 ----
        add(buildToolbar(onOpenSettings, onLogout), BorderLayout.NORTH);

        // ---- 卡片列表区域 ----
        cardsListPanel = new JPanel();
        cardsListPanel.setLayout(new BoxLayout(cardsListPanel, BoxLayout.Y_AXIS));

        JBScrollPane scrollPane = new JBScrollPane(cardsListPanel);
        scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scrollPane.setBorder(JBUI.Borders.empty());
        add(scrollPane, BorderLayout.CENTER);

        // 加载模型列表
        loadModels();
        if (CommentGeneratorClient.getModelsList() == null || CommentGeneratorClient.getModelsList().isEmpty()) {
            reloadModelsAsync();
        }

        // UI 轮询：检测卡片数据变化
        uiScheduler.scheduleWithFixedDelay(this::pollAndRefreshCards,
                Constant.UI_REFRESH_INITIAL_DELAY_MS, Constant.UI_REFRESH_DELAY_MS, TimeUnit.MILLISECONDS);
    }

    // ==================== 工具栏 ====================

    private JPanel buildToolbar(Runnable onOpenSettings, Runnable onLogout) {
        JPanel toolbar = new JPanel();
        toolbar.setLayout(new BoxLayout(toolbar, BoxLayout.Y_AXIS));
        toolbar.setBorder(JBUI.Borders.empty(6, 8, 4, 8));

        // 第一行：模型选择 + 用户操作
        JPanel row1 = new JPanel(new BorderLayout());
        row1.setOpaque(false);

        JPanel leftRow1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        leftRow1.setOpaque(false);
        leftRow1.add(modelCombo);

        JButton refreshBtn = iconButton("↻", "刷新模型列表");
        refreshBtn.addActionListener(e -> reloadModelsAsync());
        leftRow1.add(refreshBtn);

        JPanel rightRow1 = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        rightRow1.setOpaque(false);

        JBLabel userLabel = new JBLabel(AuthManager.getUsername());
        userLabel.setForeground(JBColor.GRAY);

        JButton settingsBtn = iconButton("⚙", "设置");
        settingsBtn.addActionListener(e -> onOpenSettings.run());

        JButton logoutBtn = new JButton("登出");
        logoutBtn.setFont(logoutBtn.getFont().deriveFont(11f));
        logoutBtn.putClientProperty("JButton.buttonType", "borderless");
        logoutBtn.addActionListener(e -> {
            CommentGeneratorClient.logout();
            PluginProjectService svc = project.getService(PluginProjectService.class);
            svc.setAutoUpdateEnabled(false);
            svc.setAutoCleanEnabled(false);
            onLogout.run();
        });

        rightRow1.add(userLabel);
        rightRow1.add(settingsBtn);
        rightRow1.add(logoutBtn);

        row1.add(leftRow1, BorderLayout.WEST);
        row1.add(rightRow1, BorderLayout.EAST);

        // 第二行：RAG 开关 + 更新全部
        JPanel row2 = new JPanel(new BorderLayout());
        row2.setOpaque(false);
        row2.setBorder(JBUI.Borders.emptyTop(4));

        JPanel leftRow2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        leftRow2.setOpaque(false);

        JBLabel ragLabel = new JBLabel("RAG");
        ToggleSwitch ragToggle = new ToggleSwitch(CommentGeneratorClient.isRagEnabled());
        ragToggle.addActionListener(e -> CommentGeneratorClient.setRagEnabled(ragToggle.isSelected()));
        leftRow2.add(ragLabel);
        leftRow2.add(ragToggle);

        JPanel rightRow2 = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        rightRow2.setOpaque(false);

        JButton updateAllBtn = new JButton("更新全部");
        updateAllBtn.setFont(updateAllBtn.getFont().deriveFont(11f));
        PluginProjectService autoCheckSvc = project.getService(PluginProjectService.class);
        updateAllBtn.setEnabled(!autoCheckSvc.isAutoUpdateEnabled());
        updateAllBtn.addActionListener(e -> {
            PluginProjectService svc = project.getService(PluginProjectService.class);
            if (!svc.isAutoUpdateEnabled()) {
                svc.refreshAllMethodHistories();
            }
        });
        rightRow2.add(updateAllBtn);

        row2.add(leftRow2, BorderLayout.WEST);
        row2.add(rightRow2, BorderLayout.EAST);

        toolbar.add(row1);
        toolbar.add(row2);

        // 分隔线
        toolbar.add(Box.createVerticalStrut(4));
        JSeparator sep = new JSeparator();
        sep.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
        toolbar.add(sep);

        // 模型选择事件
        modelCombo.addActionListener(e -> {
            if (suppressModelAction) return;
            String sel = (String) modelCombo.getSelectedItem();
            if (sel != null) CommentGeneratorClient.setSelectedModel(sel);
        });

        return toolbar;
    }

    private static JButton iconButton(String icon, String tooltip) {
        JButton btn = new JButton(icon);
        btn.setToolTipText(tooltip);
        btn.putClientProperty("JButton.buttonType", "borderless");
        btn.setFont(btn.getFont().deriveFont(14f));
        btn.setPreferredSize(new Dimension(28, 28));
        return btn;
    }

    // ==================== 卡片列表轮询 ====================

    private void pollAndRefreshCards() {
        List<MethodRecord> staged = historyManager.findAll().stream()
                .filter(r -> MethodStatus.TO_BE_GENERATE.equals(r.getStatus())
                        || MethodStatus.TO_BE_UPDATE.equals(r.getStatus()))
                .filter(r -> r.getStagedComment() != null && !r.getStagedComment().isBlank())
                .toList();

        Set<String> current = staged.stream()
                .map(m -> m.getKey() + "#" + m.getStagedComment())
                .collect(Collectors.toSet());

        if (!current.equals(lastSeenSignatures)) {
            lastSeenSignatures = new HashSet<>(current);
            ApplicationManager.getApplication().invokeLater(() -> refreshCardsList(staged));
        }
    }

    private void refreshCardsList(List<MethodRecord> staged) {
        cardsListPanel.removeAll();

        if (staged.isEmpty()) {
            JPanel emptyPanel = new JPanel(new GridBagLayout());
            JBLabel emptyLabel = new JBLabel("暂无待处理的注释变更");
            emptyLabel.setForeground(JBColor.GRAY);
            emptyPanel.add(emptyLabel);
            cardsListPanel.add(emptyPanel);
        } else {
            for (MethodRecord record : staged) {
                cardsListPanel.add(Box.createVerticalStrut(6));
                MethodDiffCard card = new MethodDiffCard(project, record);
                card.setAlignmentX(LEFT_ALIGNMENT);
                card.setMaximumSize(new Dimension(Integer.MAX_VALUE, card.getPreferredSize().height));
                cardsListPanel.add(card);
            }
            cardsListPanel.add(Box.createVerticalStrut(6));
            cardsListPanel.add(Box.createVerticalGlue());
        }

        cardsListPanel.revalidate();
        cardsListPanel.repaint();
    }

    // ==================== 模型列表 ====================

    private void loadModels() {
        List<String> models = CommentGeneratorClient.getModelsList();
        if (models == null || models.isEmpty()) return;
        suppressModelAction = true;
        try {
            comboBoxModel.removeAllElements();
            models.forEach(comboBoxModel::addElement);
            String sel = CommentGeneratorClient.getSelectedModel();
            if (sel != null && models.contains(sel)) {
                comboBoxModel.setSelectedItem(sel);
            } else if (!models.isEmpty()) {
                comboBoxModel.setSelectedItem(models.getFirst());
                CommentGeneratorClient.setSelectedModel(models.getFirst());
            }
        } finally {
            suppressModelAction = false;
        }
    }

    private void reloadModelsAsync() {
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            List<String> models = CommentGeneratorClient.getAvailableModels();
            ApplicationManager.getApplication().invokeLater(() -> {
                suppressModelAction = true;
                try {
                    comboBoxModel.removeAllElements();
                    models.forEach(comboBoxModel::addElement);
                    String sel = CommentGeneratorClient.getSelectedModel();
                    if (sel != null && !sel.isEmpty() && models.contains(sel)) {
                        comboBoxModel.setSelectedItem(sel);
                    } else if (!models.isEmpty()) {
                        comboBoxModel.setSelectedItem(models.getFirst());
                        CommentGeneratorClient.setSelectedModel(models.getFirst());
                    }
                } finally {
                    suppressModelAction = false;
                }
            });
        });
    }

    // ==================== 生命周期 ====================

    @Override
    public void dispose() {
        uiScheduler.shutdownNow();
    }
}
