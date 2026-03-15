package com.nju.comment.toolwindow;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.JBUI;
import com.nju.comment.client.global.CommentGeneratorClient;
import com.nju.comment.service.AuthManager;
import com.nju.comment.service.PluginProjectService;

import java.awt.*;
import javax.swing.*;

import lombok.extern.slf4j.Slf4j;


/**
 * 设置面板：API Key 管理、自动化开关、登出。
 */
@Slf4j
public class SettingsPanel extends JPanel {

    private final Project project;
    private final PluginProjectService pluginService;
    private JBLabel currentKeyLabel;
    private JBTextField newKeyField;
    private JButton saveKeyBtn;
    private JButton deleteKeyBtn;
    private JButton viewKeyBtn;

    private ToggleSwitch autoUpdateToggle;
    // 避免在 onGlobalSettingsChanged 刷新 autoUpdateToggle 时触发事件，导致回环触发。
    private boolean suppressAutoUpdateToggleEvent;

    private String currentApiKey;
    private boolean apiKeyLoaded;
    private boolean apiKeyConfiguredHint;
    private boolean showFullApiKey;
    private volatile boolean apiKeyHintSyncing;
    private static final String MASKED_API_KEY_TEXT = "********************************";

    public SettingsPanel(Project project, Runnable onBack) {
        this.project = project;
        this.pluginService = project.getService(PluginProjectService.class);
        setLayout(new BorderLayout());
        setBorder(JBUI.Borders.empty(8, 12));

        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));

        // ---- 返回按钮 ----
        JPanel backBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        backBar.setOpaque(false);
        backBar.setAlignmentX(LEFT_ALIGNMENT);
        JButton backBtn = new JButton("返回");
        backBtn.putClientProperty("JButton.buttonType", "borderless");
        backBtn.addActionListener(e -> onBack.run());
        backBar.add(backBtn);
        content.add(backBar);
        content.add(Box.createVerticalStrut(16));

        // ---- 账户 ----
        content.add(sectionHeader("账户"));
        content.add(Box.createVerticalStrut(8));

        JPanel accountRow = new JPanel(new BorderLayout());
        accountRow.setOpaque(false);
        accountRow.setAlignmentX(LEFT_ALIGNMENT);
        accountRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));

        JBLabel userLabel = new JBLabel("用户: " + AuthManager.getUsername());
        userLabel.setFont(userLabel.getFont().deriveFont(13f));

        JButton logoutBtn = new JButton("登出");
        logoutBtn.setForeground(JBColor.RED);
        logoutBtn.addActionListener(e -> CommentGeneratorClient.logout());

        accountRow.add(userLabel, BorderLayout.WEST);
        accountRow.add(logoutBtn, BorderLayout.EAST);
        content.add(accountRow);
        content.add(Box.createVerticalStrut(15));

        JSeparator sep1 = new JSeparator();
        sep1.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
        content.add(sep1);

        // ---- API Key ----
        content.add(Box.createVerticalStrut(15));
        content.add(sectionHeader("API Key"));
        content.add(Box.createVerticalStrut(8));
        content.add(buildApiKeySection());
        content.add(Box.createVerticalStrut(15));

        JSeparator sep2 = new JSeparator();
        sep2.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
        content.add(sep2);

        // ---- 自动化 ----
        content.add(Box.createVerticalStrut(15));
        content.add(sectionHeader("自动化"));
        content.add(Box.createVerticalStrut(10));
        content.add(buildAutomationSection());

        // 添加弹性空间
        content.add(Box.createVerticalGlue());

        add(content, BorderLayout.NORTH);
    }

    // ==================== API Key 区域 ====================

    private JPanel buildApiKeySection() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setOpaque(false);
        panel.setAlignmentX(LEFT_ALIGNMENT);

        // 当前 key 显示
        JPanel currentRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        currentRow.setOpaque(false);
        currentRow.setAlignmentX(LEFT_ALIGNMENT);
        currentRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));

        JBLabel keyIcon = new JBLabel("\uD83D\uDD11");
        currentKeyLabel = new JBLabel(MASKED_API_KEY_TEXT);
        currentKeyLabel.setFont(JBUI.Fonts.create(Font.MONOSPACED, 12));
        viewKeyBtn = new JButton("查看");
        viewKeyBtn.putClientProperty("JButton.buttonType", "borderless");
        viewKeyBtn.addActionListener(e -> onViewKeyToggleClicked());
        viewKeyBtn.setEnabled(true);

        currentRow.add(keyIcon);
        currentRow.add(currentKeyLabel);
        currentRow.add(viewKeyBtn);
        panel.add(currentRow);
        panel.add(Box.createVerticalStrut(10));

        // 新 key 输入
        JPanel inputRow = new JPanel(new BorderLayout(8, 0));
        inputRow.setOpaque(false);
        inputRow.setAlignmentX(LEFT_ALIGNMENT);
        inputRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));

        newKeyField = new JBTextField();
        newKeyField.getEmptyText().setText("输入新的 API Key");
        inputRow.add(newKeyField, BorderLayout.CENTER);
        panel.add(inputRow);
        panel.add(Box.createVerticalStrut(8));

        // 操作按钮
        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        btnRow.setOpaque(false);
        btnRow.setAlignmentX(LEFT_ALIGNMENT);

        saveKeyBtn = new JButton("保存 Key");
        deleteKeyBtn = new JButton("删除 Key");
        deleteKeyBtn.setForeground(JBColor.RED);

        saveKeyBtn.addActionListener(e -> doSaveKey());
        deleteKeyBtn.addActionListener(e -> doDeleteKey());

        btnRow.add(saveKeyBtn);
        btnRow.add(deleteKeyBtn);
        panel.add(btnRow);

        showFullApiKey = pluginService.isShowFullApiKeyEnabled();
        apiKeyConfiguredHint = pluginService.isApiKeyConfiguredHint();
        syncApiKeyCacheFromClient();
        renderApiKeyDisplay();
        syncApiKeyHintIfNeeded();
        return panel;
    }

    // ==================== 自动化区域 ====================

    private JPanel buildAutomationSection() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setOpaque(false);
        panel.setAlignmentX(LEFT_ALIGNMENT);

        JPanel row = new JPanel(new BorderLayout());
        row.setOpaque(false);
        row.setAlignmentX(LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
        row.setToolTipText("定期自动检测并更新所有方法的注释");

        JBLabel lbl = new JBLabel("自动更新方法注释");
        autoUpdateToggle = new ToggleSwitch(pluginService.isAutoUpdateEnabled());
        autoUpdateToggle.addActionListener(e -> {
            if (suppressAutoUpdateToggleEvent) return;
            pluginService.updateAutoUpdateEnabled(autoUpdateToggle.isSelected());
        });

        row.add(lbl, BorderLayout.WEST);
        row.add(autoUpdateToggle, BorderLayout.EAST);

        panel.add(row);
        panel.add(Box.createVerticalStrut(8));

        return panel;
    }

    // ==================== API Key 操作 ====================

    private void onViewKeyToggleClicked() {
        if (showFullApiKey) {
            applyApiKeyUiState(apiKeyConfiguredHint, false);
            renderApiKeyDisplay();
            return;
        }

        // 首次点击查看才请求后端，减少不必要的 API 调用。
        if (!apiKeyLoaded) {
            fetchApiKeyForReveal();
            return;
        }

        if (currentApiKey == null || currentApiKey.isBlank()) {
            renderApiKeyDisplay();
            return;
        }

        applyApiKeyUiState(true, true);
        renderApiKeyDisplay();
    }

    private void fetchApiKeyForReveal() {
        CommentGeneratorClient.checkApiKey(project)
                .thenAccept(apiKey ->
                        ApplicationManager.getApplication().invokeLater(() -> {
                            syncApiKeyCacheFromClient();
                            if (currentApiKey == null || currentApiKey.isBlank()) {
                                applyApiKeyUiState(false, false);
                            } else {
                                applyApiKeyUiState(true, true);
                            }
                            renderApiKeyDisplay();
                        }))
                .exceptionally(ex -> {
                    log.warn("查询 API Key 失败");
                    ApplicationManager.getApplication().invokeLater(() -> {
                        currentKeyLabel.setText("查询失败");
                        currentKeyLabel.setForeground(JBColor.RED);
                        viewKeyBtn.setText("查看");
                        viewKeyBtn.setEnabled(true);
                    });
                    return null;
                });
    }

    private void renderApiKeyDisplay() {
        if (!apiKeyLoaded) {
            if (apiKeyConfiguredHint) {
                currentKeyLabel.setText(MASKED_API_KEY_TEXT);
                currentKeyLabel.setForeground(JBColor.foreground());
            } else {
                currentKeyLabel.setText("未设置");
                currentKeyLabel.setForeground(JBColor.GRAY);
            }
            viewKeyBtn.setText("查看");
            viewKeyBtn.setEnabled(true);
            return;
        }

        if (currentApiKey == null || currentApiKey.isBlank()) {
            currentKeyLabel.setText("未设置");
            currentKeyLabel.setForeground(JBColor.GRAY);
            viewKeyBtn.setText("查看");
            viewKeyBtn.setEnabled(true);
            return;
        }

        currentKeyLabel.setForeground(JBColor.foreground());
        currentKeyLabel.setText(showFullApiKey ? currentApiKey : MASKED_API_KEY_TEXT);
        viewKeyBtn.setText(showFullApiKey ? "隐藏" : "查看");
        viewKeyBtn.setEnabled(true);
    }

    private void doSaveKey() {
        String key = newKeyField.getText().trim();
        if (key.isEmpty()) {
            Messages.showWarningDialog(project, "API Key 不能为空", "提示");
            return;
        }

        saveKeyBtn.setEnabled(false);
        CommentGeneratorClient.saveApiKey(key, project)
                .thenRun(() -> ApplicationManager.getApplication().invokeLater(() -> {
                    saveKeyBtn.setEnabled(true);
                    newKeyField.setText("");
                    syncApiKeyCacheFromClient();
                    applyApiKeyUiState(true, showFullApiKey);
                    renderApiKeyDisplay();
                    Messages.showInfoMessage(project, "API Key 保存成功", "提示");
                }))
                .exceptionally(ex -> {
                    String msg = extractError(ex);
                    log.warn("保存 API Key 失败: {}", msg);
                    ApplicationManager.getApplication().invokeLater(() -> {
                        saveKeyBtn.setEnabled(true);
                        Messages.showErrorDialog(project, "保存失败: " + msg, "错误");
                    });
                    return null;
                });
    }

    private void doDeleteKey() {
        int confirm = Messages.showYesNoDialog(project, "确认删除当前 API Key？", "确认", Messages.getQuestionIcon());
        if (confirm != Messages.YES) return;

        deleteKeyBtn.setEnabled(false);
        CommentGeneratorClient.deleteApiKey(project)
                .thenRun(() -> ApplicationManager.getApplication().invokeLater(() -> {
                    deleteKeyBtn.setEnabled(true);
                    syncApiKeyCacheFromClient();
                    applyApiKeyUiState(false, false);
                    renderApiKeyDisplay();
                    Messages.showInfoMessage(project, "API Key 已删除", "提示");
                }))
                .exceptionally(ex -> {
                    String msg = extractError(ex);
                    log.warn("删除 API Key 失败: {}", msg);
                    ApplicationManager.getApplication().invokeLater(() -> {
                        deleteKeyBtn.setEnabled(true);
                        Messages.showErrorDialog(project, "删除失败: " + msg, "错误");
                    });
                    return null;
                });
    }

    // ==================== UI 工具方法 ====================

    /**
     * 响应跨项目全局配置变更，原位刷新当前设置页，避免重建面板导致闪烁。
     */
    public void onGlobalSettingsChanged() {
        syncApiKeyCacheFromClient();
        showFullApiKey = pluginService.isShowFullApiKeyEnabled();
        apiKeyConfiguredHint = pluginService.isApiKeyConfiguredHint();
        if (autoUpdateToggle != null) {
            suppressAutoUpdateToggleEvent = true;
            try {
                autoUpdateToggle.setSelected(pluginService.isAutoUpdateEnabled());
            } finally {
                suppressAutoUpdateToggleEvent = false;
            }
        }
        renderApiKeyDisplay();
        syncApiKeyHintIfNeeded();
    }

    private void syncApiKeyHintIfNeeded() {
        if (apiKeyHintSyncing || apiKeyLoaded || apiKeyConfiguredHint) {
            return;
        }
        apiKeyHintSyncing = true;
        currentKeyLabel.setText("检测中...");
        currentKeyLabel.setForeground(JBColor.GRAY);
        viewKeyBtn.setEnabled(false);

        CommentGeneratorClient.checkApiKeySilently()
                .thenAccept(apiKey -> ApplicationManager.getApplication().invokeLater(() -> {
                    syncApiKeyCacheFromClient();
                    boolean configured = currentApiKey != null && !currentApiKey.isBlank();
                    boolean showFull = configured && showFullApiKey;
                    applyApiKeyUiState(configured, showFull);
                    renderApiKeyDisplay();
                }))
                .whenComplete((r, ex) -> apiKeyHintSyncing = false);
    }

    private void syncApiKeyCacheFromClient() {
        apiKeyLoaded = CommentGeneratorClient.isApiKeyLoaded();
        currentApiKey = CommentGeneratorClient.getCachedApiKey();
    }

    private void applyApiKeyUiState(boolean configured, boolean showFull) {
        apiKeyConfiguredHint = configured;
        showFullApiKey = showFull;
        pluginService.updateApiKeyUiState(configured, showFull);
    }

    private static JBLabel sectionHeader(String text) {
        JBLabel label = new JBLabel(text);
        label.setFont(label.getFont().deriveFont(Font.BOLD, 14f));
        label.setAlignmentX(LEFT_ALIGNMENT);
        return label;
    }

    private static String extractError(Throwable ex) {
        Throwable cause = ex;
        while (cause.getCause() != null) cause = cause.getCause();
        return cause.getMessage() != null ? cause.getMessage() : "未知错误";
    }
}
