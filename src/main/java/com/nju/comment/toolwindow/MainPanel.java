package com.nju.comment.toolwindow;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;
import com.nju.comment.service.AuthManager;
import com.nju.comment.client.global.CommentGeneratorClient;
import com.nju.comment.constant.Constant;
import com.nju.comment.pojo.MethodRecord;
import com.nju.comment.pojo.MethodStatus;
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
    private final PluginProjectService pluginService;

    private final DefaultComboBoxModel<String> comboBoxModel;
    private final ComboBox<String> modelCombo;
    private final JPanel cardsListPanel;

    private final ScheduledExecutorService uiScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "main-panel-ui-poller");
        t.setDaemon(true);
        return t;
    });
    private Set<String> lastSeenSignatures = null;
    private boolean suppressModelAction = false;
    private boolean suppressRagToggleAction = false;
    private boolean suppressRagExampleChange = false;
    private ToggleSwitch ragToggle;
    private JPanel ragExampleHolder;
    private ComboBox<Integer> ragExampleCombo;


    public MainPanel(Project project, Runnable onOpenSettings) {
        this.project = project;
        this.pluginService = project.getService(PluginProjectService.class);
        setLayout(new BorderLayout());

        comboBoxModel = new DefaultComboBoxModel<>();
        modelCombo = new ComboBox<>(comboBoxModel);
        modelCombo.setPreferredSize(new Dimension(180, 28));

        // ---- 工具栏 ----
        add(buildToolbar(onOpenSettings), BorderLayout.NORTH);

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

    private JPanel buildToolbar(Runnable onOpenSettings) {
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
        logoutBtn.putClientProperty("JButton.buttonType", "borderless");
        logoutBtn.addActionListener(e -> CommentGeneratorClient.logout());

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
        ragToggle = new ToggleSwitch(CommentGeneratorClient.isRagEnabled());
        ragToggle.addActionListener(e -> {
            if (suppressRagToggleAction) return;
            boolean enabled = ragToggle.isSelected();
            applyRagExampleVisibility(enabled);
            pluginService.updateRagEnabled(enabled);
        });
        leftRow2.add(ragLabel);
        leftRow2.add(ragToggle);

        JPanel ragExamplePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        ragExamplePanel.setOpaque(false);
        ragExamplePanel.add(new JBLabel("示例数"));

        ragExampleCombo = new ComboBox<>(new Integer[]{1, 2, 3, 4, 5});
        ragExampleCombo.setPreferredSize(new Dimension(60, 24));
        ragExampleCombo.setSelectedItem(normalizeRagExampleNum(pluginService.getRagExampleNum()));
        ragExampleCombo.addActionListener(e -> {
            if (suppressRagExampleChange) return;
            Integer selected = (Integer) ragExampleCombo.getSelectedItem();
            int value = normalizeRagExampleNum(selected == null ? 3 : selected);
            pluginService.updateRagExampleNum(value);
        });
        ragExamplePanel.add(ragExampleCombo);

        JPanel ragExamplePlaceholder = new JPanel();
        ragExamplePlaceholder.setOpaque(false);
        Dimension ragExampleSize = ragExamplePanel.getPreferredSize();
        ragExamplePlaceholder.setPreferredSize(ragExampleSize);
        ragExamplePlaceholder.setMinimumSize(ragExampleSize);
        ragExamplePlaceholder.setMaximumSize(ragExampleSize);

        ragExampleHolder = new JPanel(new CardLayout());
        ragExampleHolder.setOpaque(false);
        ragExampleHolder.add(ragExamplePanel, "visible");
        ragExampleHolder.add(ragExamplePlaceholder, "hidden");
        applyRagExampleVisibility(ragToggle.isSelected());
        leftRow2.add(ragExampleHolder);

        JPanel rightRow2 = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        rightRow2.setOpaque(false);

        JButton updateAllBtn = new JButton("更新全部");
        updateAllBtn.setEnabled(!pluginService.isAutoUpdateEnabled());
        updateAllBtn.addActionListener(e -> {
            if (!pluginService.isAutoUpdateEnabled()) {
                pluginService.refreshAllMethodHistories();
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
            if (sel != null) {
                pluginService.updateSelectedModel(sel);
            }
        });

        return toolbar;
    }

    private static JButton iconButton(String icon, String tooltip) {
        JButton btn = new JButton(icon);
        btn.setToolTipText(tooltip);
        btn.putClientProperty("JButton.buttonType", "borderless");
        btn.setPreferredSize(new Dimension(28, 28));
        return btn;
    }

    // ==================== 卡片列表轮询 ====================

    private void pollAndRefreshCards() {
        List<MethodRecord> staged = pluginService.getMethodHistoryManager().findAll().stream()
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
                comboBoxModel.setSelectedItem(models.get(0));
                pluginService.updateSelectedModel(models.get(0));
            }
        } finally {
            suppressModelAction = false;
        }
    }

    private void reloadModelsAsync() {
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            List<String> models = CommentGeneratorClient.getAvailableModels(project);
            ApplicationManager.getApplication().invokeLater(() -> {
                suppressModelAction = true;
                try {
                    comboBoxModel.removeAllElements();
                    models.forEach(comboBoxModel::addElement);
                    String sel = CommentGeneratorClient.getSelectedModel();
                    if (sel != null && !sel.isEmpty() && models.contains(sel)) {
                        comboBoxModel.setSelectedItem(sel);
                    } else if (!models.isEmpty()) {
                        comboBoxModel.setSelectedItem(models.get(0));
                        pluginService.updateSelectedModel(models.get(0));
                    }
                } finally {
                    suppressModelAction = false;
                }
            });
        });
    }

    public void onGlobalSettingsChanged() {
        suppressModelAction = true;
        try {
            String selected = CommentGeneratorClient.getSelectedModel();
            if (selected != null && comboBoxModel.getSize() > 0) {
                comboBoxModel.setSelectedItem(selected);
            }
        } finally {
            suppressModelAction = false;
        }

        if (ragToggle != null) {
            suppressRagToggleAction = true;
            try {
                ragToggle.setSelected(CommentGeneratorClient.isRagEnabled());
            } finally {
                suppressRagToggleAction = false;
            }
        }

        applyRagExampleVisibility(CommentGeneratorClient.isRagEnabled());

        if (ragExampleCombo != null) {
            int value = normalizeRagExampleNum(pluginService.getRagExampleNum());
            suppressRagExampleChange = true;
            try {
                ragExampleCombo.setSelectedItem(value);
            } finally {
                suppressRagExampleChange = false;
            }
        }
    }

    private static int normalizeRagExampleNum(int value) {
        return Math.max(1, Math.min(5, value));
    }

    private void applyRagExampleVisibility(boolean visible) {
        if (ragExampleHolder == null) return;
        LayoutManager layout = ragExampleHolder.getLayout();
        if (layout instanceof CardLayout cardLayout) {
            cardLayout.show(ragExampleHolder, visible ? "visible" : "hidden");
        }
        ragExampleHolder.revalidate();
        ragExampleHolder.repaint();
    }

    // ==================== 生命周期 ====================

    @Override
    public void dispose() {
        uiScheduler.shutdownNow();
    }
}
