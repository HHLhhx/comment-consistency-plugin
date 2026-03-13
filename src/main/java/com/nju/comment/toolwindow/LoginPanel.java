package com.nju.comment.toolwindow;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPasswordField;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.JBUI;
import com.nju.comment.client.global.CommentGeneratorClient;
import lombok.extern.slf4j.Slf4j;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.JTextComponent;
import java.awt.*;

/**
 * 内嵌在 ToolWindow 中的登录/注册面板，通过 CardLayout 在登录视图与注册视图间切换。
 */
@Slf4j
public class LoginPanel extends JPanel {

    private static final String VIEW_LOGIN = "login";
    private static final String VIEW_REGISTER = "register";

    private static final Color BLUE_BTN_BG = new JBColor(new Color(3, 102, 214), new Color(47, 111, 209));

    private final CardLayout cardLayout;
    private final JPanel cardPanel;

    // ---- 登录视图 ----
    private JBTextField loginUserField;
    private JBPasswordField loginPassField;
    private JButton loginBtn;
    private JBLabel loginStatus;

    // ---- 注册视图 ----
    private JBTextField regUserField;
    private JBPasswordField regPassField;
    private JBTextField regPhoneField;
    private JButton regBtn;
    private JButton regBackBtn;
    private JBLabel regStatus;

    public LoginPanel() {
        setLayout(new GridBagLayout());

        cardLayout = new CardLayout();
        cardPanel = new JPanel(cardLayout);
        cardPanel.setOpaque(false);

        cardPanel.add(buildLoginView(), VIEW_LOGIN);
        cardPanel.add(buildRegisterView(), VIEW_REGISTER);

        add(cardPanel);
        cardLayout.show(cardPanel, VIEW_LOGIN);
    }

    // ==================== 登录视图 ====================

    private JPanel buildLoginView() {
        JPanel card = new JPanel();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBorder(JBUI.Borders.empty(32, 40));
        card.setOpaque(false);

        JBLabel title = centeredLabel("Comment Consistency", Font.BOLD, 20f);
        JBLabel subtitle = centeredLabel("登录以使用注释一致性检测服务", Font.PLAIN, 12f);
        subtitle.setForeground(JBColor.GRAY);

        // 表单
        JPanel form = formContainer();
        loginUserField = addField(form, "用户名");
        loginPassField = addPasswordField(form, "密码");

        // 按钮
        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 0));
        btnPanel.setOpaque(false);

        loginBtn = blueButton("登 录");
        JButton toRegBtn = new JButton("注 册");
        toRegBtn.setPreferredSize(new Dimension(100, 32));
        toRegBtn.addActionListener(e -> {
            loginStatus.setText(" ");
            cardLayout.show(cardPanel, VIEW_REGISTER);
        });

        btnPanel.add(loginBtn);
        btnPanel.add(toRegBtn);

        loginStatus = statusLabel();

        card.add(title);
        card.add(Box.createVerticalStrut(6));
        card.add(subtitle);
        card.add(Box.createVerticalStrut(24));
        card.add(form);
        card.add(Box.createVerticalStrut(20));
        card.add(btnPanel);
        card.add(Box.createVerticalStrut(8));
        card.add(loginStatus);

        loginBtn.addActionListener(e -> doLogin());
        loginPassField.addActionListener(e -> doLogin());
        attachClearOnType(loginUserField, loginStatus);
        attachClearOnType(loginPassField, loginStatus);
        card.add(Box.createVerticalGlue());
        return card;
    }

    // ==================== 注册视图 ====================

    private JPanel buildRegisterView() {
        JPanel card = new JPanel();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBorder(JBUI.Borders.empty(32, 40));
        card.setOpaque(false);

        JBLabel title = centeredLabel("Comment Consistency", Font.BOLD, 20f);
        JBLabel subtitle = centeredLabel("创建新账户", Font.PLAIN, 12f);
        subtitle.setForeground(JBColor.GRAY);

        // 表单
        JPanel form = formContainer();
        regUserField = addField(form, "用户名");
        regPassField = addPasswordField(form, "密码");
        regPhoneField = addField(form, "电话号码");

        // 按钮
        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 0));
        btnPanel.setOpaque(false);

        regBackBtn = new JButton("返 回");
        regBackBtn.setPreferredSize(new Dimension(100, 32));
        regBackBtn.addActionListener(e -> {
            regStatus.setText(" ");
            cardLayout.show(cardPanel, VIEW_LOGIN);
        });

        regBtn = blueButton("注 册");

        btnPanel.add(regBackBtn);
        btnPanel.add(regBtn);

        regStatus = statusLabel();

        card.add(title);
        card.add(Box.createVerticalStrut(6));
        card.add(subtitle);
        card.add(Box.createVerticalStrut(24));
        card.add(form);
        card.add(Box.createVerticalStrut(20));
        card.add(btnPanel);
        card.add(Box.createVerticalStrut(8));
        card.add(regStatus);

        regBtn.addActionListener(e -> doRegister());
        regPhoneField.addActionListener(e -> doRegister());
        attachClearOnType(regUserField, regStatus);
        attachClearOnType(regPassField, regStatus);
        attachClearOnType(regPhoneField, regStatus);
        card.add(Box.createVerticalGlue());
        return card;
    }

    // ==================== 登录/注册逻辑 ====================

    private void doLogin() {
        String user = loginUserField.getText().trim();
        String pass = new String(loginPassField.getPassword());
        if (user.isEmpty() || pass.isEmpty()) {
            showStatus(loginStatus, "用户名和密码不能为空", true);
            return;
        }

        setLoginControlsEnabled(false);
        showStatus(loginStatus, "登录中...", false);

        CommentGeneratorClient.login(user, pass)
                .exceptionally(ex -> {
                    String msg = extractError(ex);
                    log.warn("登录失败: {}", msg);
                    ApplicationManager.getApplication().invokeLater(() -> {
                        setLoginControlsEnabled(true);
                        showStatus(loginStatus, "登录失败: " + msg, true);
                    });
                    return null;
                });
    }

    private void doRegister() {
        String user = regUserField.getText().trim();
        String pass = new String(regPassField.getPassword());
        String phone = regPhoneField.getText().trim();
        if (user.isEmpty() || pass.isEmpty()) {
            showStatus(regStatus, "用户名和密码不能为空", true);
            return;
        }
        if (phone.isEmpty()) {
            showStatus(regStatus, "电话号码不能为空", true);
            return;
        }

        setRegControlsEnabled(false);
        showStatus(regStatus, "注册中...", false);

        CommentGeneratorClient.register(user, pass, phone)
                .exceptionally(ex -> {
                    String msg = extractError(ex);
                    log.warn("注册失败: {}", msg);
                    ApplicationManager.getApplication().invokeLater(() -> {
                        setRegControlsEnabled(true);
                        showStatus(regStatus, "注册失败: " + msg, true);
                    });
                    return null;
                });
    }

    // ==================== 控件状态 ====================

    private void setLoginControlsEnabled(boolean enabled) {
        loginBtn.setEnabled(enabled);
        loginUserField.setEnabled(enabled);
        loginPassField.setEnabled(enabled);
    }

    private void setRegControlsEnabled(boolean enabled) {
        regBtn.setEnabled(enabled);
        regBackBtn.setEnabled(enabled);
        regUserField.setEnabled(enabled);
        regPassField.setEnabled(enabled);
        regPhoneField.setEnabled(enabled);
    }

    // ==================== UI 工具 ====================

    private static JBLabel centeredLabel(String text, int style, float size) {
        JBLabel lbl = new JBLabel(text);
        lbl.setFont(lbl.getFont().deriveFont(style, size));
        lbl.setAlignmentX(CENTER_ALIGNMENT);
        return lbl;
    }

    private static JPanel formContainer() {
        JPanel form = new JPanel();
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        form.setOpaque(false);
        form.setAlignmentX(CENTER_ALIGNMENT);
        form.setMaximumSize(new Dimension(260, 280));
        return form;
    }

    private static JBTextField addField(JPanel form, String label) {
        JBLabel lbl = new JBLabel(label);
        lbl.setAlignmentX(LEFT_ALIGNMENT);
        JBTextField field = new JBTextField();
        field.setAlignmentX(LEFT_ALIGNMENT);
        field.setMaximumSize(new Dimension(260, 34));
        field.setPreferredSize(new Dimension(260, 34));
        form.add(lbl);
        form.add(Box.createVerticalStrut(4));
        form.add(field);
        form.add(Box.createVerticalStrut(12));
        return field;
    }

    private static JBPasswordField addPasswordField(JPanel form, String label) {
        JBLabel lbl = new JBLabel(label);
        lbl.setAlignmentX(LEFT_ALIGNMENT);
        JBPasswordField field = new JBPasswordField();
        field.setAlignmentX(LEFT_ALIGNMENT);
        field.setMaximumSize(new Dimension(260, 34));
        field.setPreferredSize(new Dimension(260, 34));
        form.add(lbl);
        form.add(Box.createVerticalStrut(4));
        form.add(field);
        form.add(Box.createVerticalStrut(12));
        return field;
    }

    private static JButton blueButton(String text) {
        JButton btn = new JButton(text);
        btn.setPreferredSize(new Dimension(100, 32));
        btn.setBackground(BLUE_BTN_BG);
        btn.setOpaque(true);
        btn.setBorderPainted(false);
        return btn;
    }

    private static JBLabel statusLabel() {
        JBLabel lbl = new JBLabel(" ");
        lbl.setAlignmentX(CENTER_ALIGNMENT);
        lbl.setForeground(JBColor.RED);
        lbl.setFont(lbl.getFont().deriveFont(11f));
        return lbl;
    }

    private static void showStatus(JBLabel label, String text, boolean isError) {
        label.setForeground(isError ? JBColor.RED : JBColor.GRAY);
        label.setText(text);
    }

    private static void attachClearOnType(JTextComponent field, JBLabel statusLabel) {
        field.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                statusLabel.setText(" ");
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                statusLabel.setText(" ");
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                statusLabel.setText(" ");
            }
        });
    }

    private static String extractError(Throwable ex) {
        Throwable cause = ex;
        while (cause.getCause() != null) cause = cause.getCause();
        return cause.getMessage() != null ? cause.getMessage() : "未知错误";
    }
}
