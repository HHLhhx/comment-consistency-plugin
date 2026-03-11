package com.nju.comment.toolwindow;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.JBUI;
import com.nju.comment.client.global.AuthManager;
import com.nju.comment.client.global.CommentGeneratorClient;
import com.nju.comment.service.PluginProjectService;
import lombok.extern.slf4j.Slf4j;

import javax.swing.*;
import java.awt.*;
import java.util.function.Consumer;

/**
 * 设置面板：API Key 管理、自动化开关、登出。
 */
@Slf4j
public class  SettingsPanel extends JPanel {

    private final Project project;
    private JBLabel currentKeyLabel;
    private JBTextField newKeyField;
    private JButton saveKeyBtn;
    private JButton deleteKeyBtn;

    public SettingsPanel(Project project, Runnable onBack, Runnable onLogout) {
        this.project = project;
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
        logoutBtn.addActionListener(e -> {
            CommentGeneratorClient.logout();
            PluginProjectService svc = project.getService(PluginProjectService.class);
            svc.setAutoUpdateEnabled(false);
            svc.setAutoCleanEnabled(false);
            onLogout.run();
        });

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
        currentKeyLabel = new JBLabel("加载中...");
        currentKeyLabel.setFont(JBUI.Fonts.create(Font.MONOSPACED, 12));

        currentRow.add(keyIcon);
        currentRow.add(currentKeyLabel);
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

        loadCurrentKey();
        return panel;
    }

    // ==================== 自动化区域 ====================

    private JPanel buildAutomationSection() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setOpaque(false);
        panel.setAlignmentX(LEFT_ALIGNMENT);

        PluginProjectService service = project.getService(PluginProjectService.class);

        panel.add(toggleRow("自动更新方法注释",
                "定期自动检测并更新所有方法的注释",
                service.isAutoUpdateEnabled(),
                service::setAutoUpdateEnabled));
        panel.add(Box.createVerticalStrut(8));

        return panel;
    }

    // ==================== API Key 操作 ====================

    private void loadCurrentKey() {
        CommentGeneratorClient.checkApiKey()
                .thenAccept(maskedKey -> ApplicationManager.getApplication().invokeLater(() -> {
                    if (maskedKey == null || maskedKey.isBlank()) {
                        currentKeyLabel.setText("未设置");
                        currentKeyLabel.setForeground(JBColor.GRAY);
                    } else {
                        currentKeyLabel.setText(maskedKey);
                        currentKeyLabel.setForeground(JBColor.foreground());
                    }
                }))
                .exceptionally(ex -> {
                    log.warn("查询 API Key 失败", ex);
                    ApplicationManager.getApplication().invokeLater(() -> {
                        currentKeyLabel.setText("查询失败");
                        currentKeyLabel.setForeground(JBColor.RED);
                    });
                    return null;
                });
    }

    private void doSaveKey() {
        String key = newKeyField.getText().trim();
        if (key.isEmpty()) {
            Messages.showWarningDialog(project, "API Key 不能为空", "提示");
            return;
        }

        saveKeyBtn.setEnabled(false);
        CommentGeneratorClient.saveApiKey(key)
                .thenRun(() -> ApplicationManager.getApplication().invokeLater(() -> {
                    saveKeyBtn.setEnabled(true);
                    newKeyField.setText("");
                    loadCurrentKey();
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
        CommentGeneratorClient.deleteApiKey()
                .thenRun(() -> ApplicationManager.getApplication().invokeLater(() -> {
                    deleteKeyBtn.setEnabled(true);
                    loadCurrentKey();
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

    private static JBLabel sectionHeader(String text) {
        JBLabel label = new JBLabel(text);
        label.setFont(label.getFont().deriveFont(Font.BOLD, 14f));
        label.setAlignmentX(LEFT_ALIGNMENT);
        return label;
    }

    private static JPanel toggleRow(String label, String tooltip,
                                    boolean initialValue, Consumer<Boolean> onChange) {
        JPanel row = new JPanel(new BorderLayout());
        row.setOpaque(false);
        row.setAlignmentX(LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
        row.setToolTipText(tooltip);

        JBLabel lbl = new JBLabel(label);
        ToggleSwitch toggle = new ToggleSwitch(initialValue);
        toggle.addActionListener(e -> onChange.accept(toggle.isSelected()));

        row.add(lbl, BorderLayout.WEST);
        row.add(toggle, BorderLayout.EAST);
        return row;
    }

    private static String extractError(Throwable ex) {
        Throwable cause = ex;
        while (cause.getCause() != null) cause = cause.getCause();
        return cause.getMessage() != null ? cause.getMessage() : "未知错误";
    }
}
