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
import java.util.regex.Pattern;

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
    private JBTextField regEmailField;
    private JBPasswordField regPassField;
    private JBPasswordField regConfirmPassField;
    private JBTextField regEmailCodeField;
    private JButton sendCodeBtn;
    private JButton regBtn;
    private JButton regBackBtn;
    private JBLabel regStatus;

    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+$");

    private static final int CODE_COOLDOWN_SECONDS = 60;
    private Timer sendCodeCooldownTimer;
    private int remainingCooldownSeconds;

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
        loginUserField = addField(form, "用户名或邮箱");
        loginPassField = addPasswordField(form, "密码");

        // 按钮
        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 0));
        btnPanel.setOpaque(false);

        loginBtn = blueButton("登 录");
        JButton toRegBtn = new JButton("注 册");
        toRegBtn.setPreferredSize(new Dimension(100, 32));
        toRegBtn.putClientProperty("JButton.buttonType", "borderless");
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
        regEmailField = addField(form, "邮箱");
        regPassField = addPasswordField(form, "密码");
        regConfirmPassField = addPasswordField(form, "确认密码");
        JBLabel codeLabel = new JBLabel("邮箱验证码");
        codeLabel.setAlignmentX(LEFT_ALIGNMENT);

        JPanel codePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        codePanel.setOpaque(false);
        codePanel.setAlignmentX(LEFT_ALIGNMENT);
        regEmailCodeField = new JBTextField();
        regEmailCodeField.setPreferredSize(new Dimension(132, 34));
        regEmailCodeField.setMaximumSize(new Dimension(132, 34));

        sendCodeBtn = new JButton("发送验证码");
        sendCodeBtn.setPreferredSize(new Dimension(120, 34));
        sendCodeBtn.putClientProperty("JButton.buttonType", "borderless");
        sendCodeBtn.addActionListener(e -> doSendCode());

        codePanel.add(regEmailCodeField);
        codePanel.add(sendCodeBtn);

        form.add(codeLabel);
        form.add(Box.createVerticalStrut(4));
        form.add(codePanel);
        form.add(Box.createVerticalStrut(12));

        // 按钮
        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 0));
        btnPanel.setOpaque(false);

        regBackBtn = new JButton("返 回");
        regBackBtn.setPreferredSize(new Dimension(100, 32));
        regBackBtn.putClientProperty("JButton.buttonType", "borderless");
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
        regEmailCodeField.addActionListener(e -> doRegister());
        attachClearOnType(regUserField, regStatus);
        attachClearOnType(regEmailField, regStatus);
        attachClearOnType(regPassField, regStatus);
        attachClearOnType(regConfirmPassField, regStatus);
        attachClearOnType(regEmailCodeField, regStatus);
        card.add(Box.createVerticalGlue());
        return card;
    }

    // ==================== 登录/注册逻辑 ====================

    private void doLogin() {
        String account = loginUserField.getText().trim();
        String pass = new String(loginPassField.getPassword());
        if (account.isEmpty() || pass.isEmpty()) {
            showStatus(loginStatus, "账号和密码不能为空", true);
            return;
        }

        setLoginControlsEnabled(false);
        showStatus(loginStatus, "登录中...", false);

        CommentGeneratorClient.login(account, pass)
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
        String email = regEmailField.getText().trim();
        String pass = new String(regPassField.getPassword());
        String confirmPass = new String(regConfirmPassField.getPassword());
        String emailCode = regEmailCodeField.getText().trim();

        if (user.isEmpty() || email.isEmpty() || pass.isEmpty() || confirmPass.isEmpty() || emailCode.isEmpty()) {
            showStatus(regStatus, "请完整填写注册信息", true);
            return;
        }
        if (!isEmail(email)) {
            showStatus(regStatus, "邮箱格式不正确", true);
            return;
        }
        if (!pass.equals(confirmPass)) {
            showStatus(regStatus, "两次输入密码不一致", true);
            return;
        }

        setRegControlsEnabled(false);
        showStatus(regStatus, "注册中...", false);

        CommentGeneratorClient.register(user, email, pass, confirmPass, emailCode)
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

    private void doSendCode() {
        String email = regEmailField.getText().trim();
        if (!isEmail(email)) {
            showStatus(regStatus, "请输入合法邮箱后再发送验证码", true);
            return;
        }
        if (remainingCooldownSeconds > 0) {
            return;
        }

        sendCodeBtn.setEnabled(false);
        showStatus(regStatus, "验证码发送中...", false);

        CommentGeneratorClient.sendRegisterEmailCode(email)
                .whenComplete((r, ex) -> ApplicationManager.getApplication().invokeLater(() -> {
                    if (ex == null) {
                        showStatus(regStatus, "验证码已发送，请检查邮箱", false);
                        startCooldown();
                    } else {
                        sendCodeBtn.setEnabled(true);
                        sendCodeBtn.setText("发送验证码");
                        String msg = extractError(ex);
                        log.warn("发送验证码失败: {}", msg);
                        showStatus(regStatus, "发送失败: " + msg, true);
                    }
                }));
    }

    private void startCooldown() {
        remainingCooldownSeconds = CODE_COOLDOWN_SECONDS;
        updateSendCodeButtonText();
        sendCodeBtn.setEnabled(false);

        if (sendCodeCooldownTimer != null) {
            sendCodeCooldownTimer.stop();
        }

        sendCodeCooldownTimer = new Timer(1000, e -> {
            remainingCooldownSeconds--;
            if (remainingCooldownSeconds <= 0) {
                remainingCooldownSeconds = 0;
                sendCodeCooldownTimer.stop();
                sendCodeBtn.setText("发送验证码");
                sendCodeBtn.setEnabled(true);
            } else {
                updateSendCodeButtonText();
            }
        });
        sendCodeCooldownTimer.start();
    }

    private void updateSendCodeButtonText() {
        sendCodeBtn.setText(remainingCooldownSeconds + "s后重试");
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
        regEmailField.setEnabled(enabled);
        regPassField.setEnabled(enabled);
        regConfirmPassField.setEnabled(enabled);
        regEmailCodeField.setEnabled(enabled);
        if (!enabled) {
            sendCodeBtn.setEnabled(false);
            return;
        }
        sendCodeBtn.setEnabled(remainingCooldownSeconds == 0);
    }

    private static boolean isEmail(String email) {
        return EMAIL_PATTERN.matcher(email).matches();
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
        form.setMaximumSize(new Dimension(320, 320));
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
        btn.putClientProperty("JButton.buttonType", "borderless");
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
