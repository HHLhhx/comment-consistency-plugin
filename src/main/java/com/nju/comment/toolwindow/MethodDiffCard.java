package com.nju.comment.toolwindow;

import com.intellij.diff.DiffContentFactory;
import com.intellij.diff.DiffManager;
import com.intellij.diff.requests.SimpleDiffRequest;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiElementFactory;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.javadoc.PsiDocComment;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBUI;
import com.nju.comment.pojo.MethodRecord;
import com.nju.comment.pojo.MethodStatus;
import com.nju.comment.history.MethodHistoryManager;
import com.nju.comment.service.PluginProjectService;
import com.nju.comment.util.MethodRecordUtil;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * 方法注释变更卡片。
 * <p>
 * 卡片内展示 git 风格的红绿 diff 预览，支持定位、查看完整差异、应用及忽略操作。
 */
public class MethodDiffCard extends JPanel {

    private static final int MAX_PREVIEW_LINES = 8;

    // diff 行背景色
    private static final Color BG_REMOVED = new JBColor(new Color(255, 235, 233), new Color(67, 30, 30));
    private static final Color BG_ADDED = new JBColor(new Color(230, 255, 237), new Color(28, 56, 34));
    private static final Color FG_REMOVED = new JBColor(new Color(179, 29, 40), new Color(248, 129, 130));
    private static final Color FG_ADDED = new JBColor(new Color(34, 134, 58), new Color(126, 231, 135));

    // 状态 badge 颜色
    private static final Color BADGE_GEN_BG = new JBColor(new Color(218, 237, 255), new Color(27, 58, 92));
    private static final Color BADGE_GEN_FG = new JBColor(new Color(3, 102, 214), new Color(88, 166, 255));
    private static final Color BADGE_UPD_BG = new JBColor(new Color(255, 243, 205), new Color(92, 74, 27));
    private static final Color BADGE_UPD_FG = new JBColor(new Color(133, 100, 4), new Color(255, 214, 102));

    private static final Font MONO_FONT = JBUI.Fonts.create(Font.MONOSPACED, 12);

    private final Project project;
    private final MethodRecord record;
    private final MethodHistoryManager historyManager;

    public MethodDiffCard(Project project, MethodRecord record) {
        this.project = project;
        this.record = record;
        this.historyManager = project.getService(PluginProjectService.class).getMethodHistoryManager();

        setLayout(new BorderLayout());
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(JBColor.border(), 1),
                JBUI.Borders.empty()
        ));

        add(buildHeader(), BorderLayout.NORTH);
        add(buildDiffPreview(), BorderLayout.CENTER);
        add(buildActions(), BorderLayout.SOUTH);
    }

    // ==================== Header ====================

    private JPanel buildHeader() {
        JPanel header = new JPanel(new BorderLayout());
        header.setBorder(JBUI.Borders.empty(8, 10, 4, 10));
        header.setOpaque(false);

        JPanel left = new JPanel();
        left.setLayout(new BoxLayout(left, BoxLayout.Y_AXIS));
        left.setOpaque(false);

        if (record.getQualifiedNameContainClass() != null) {
            JBLabel className = new JBLabel(record.getQualifiedNameContainClass());
            className.setFont(className.getFont().deriveFont(10f));
            className.setForeground(JBColor.GRAY);
            left.add(className);
            left.add(Box.createVerticalStrut(2));
        }

        String sigText = record.getSignature() != null ? record.getSignature() : record.getKey();
        JBLabel methodSig = new JBLabel(sigText);
        methodSig.setFont(methodSig.getFont().deriveFont(Font.BOLD, 12f));
        left.add(methodSig);

        header.add(left, BorderLayout.CENTER);
        header.add(createBadge(), BorderLayout.EAST);

        return header;
    }

    private JBLabel createBadge() {
        boolean isGenerate = MethodStatus.TO_BE_GENERATE.equals(record.getStatus());
        String text = isGenerate ? "GENERATE" : "UPDATE";
        Color bg = isGenerate ? BADGE_GEN_BG : BADGE_UPD_BG;
        Color fg = isGenerate ? BADGE_GEN_FG : BADGE_UPD_FG;

        JBLabel badge = new JBLabel(text) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(bg);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 8, 8);
                g2.dispose();
                super.paintComponent(g);
            }
        };
        badge.setFont(badge.getFont().deriveFont(Font.BOLD, 10f));
        badge.setForeground(fg);
        badge.setBorder(JBUI.Borders.empty(2, 8));
        badge.setOpaque(false);
        return badge;
    }

    // ==================== Diff preview ====================

    private JComponent buildDiffPreview() {
        List<DiffLine> diffLines = computeDiff(record.getOldComment(), record.getStagedComment());

        JPanel diffPanel = new JPanel();
        diffPanel.setLayout(new BoxLayout(diffPanel, BoxLayout.Y_AXIS));
        diffPanel.setBorder(JBUI.Borders.empty(4, 10));
        diffPanel.setOpaque(false);

        if (diffLines.size() <= MAX_PREVIEW_LINES) {
            for (DiffLine diffLine : diffLines) {
                diffPanel.add(createDiffLineComponent(diffLine));
            }
            return diffPanel;
        }

        final boolean[] expanded = {false};
        final Runnable[] refresh = new Runnable[1];

        refresh[0] = () -> {
            diffPanel.removeAll();

            int shown = expanded[0] ? diffLines.size() : MAX_PREVIEW_LINES;
            for (int i = 0; i < shown; i++) {
                diffPanel.add(createDiffLineComponent(diffLines.get(i)));
            }

            int hidden = diffLines.size() - MAX_PREVIEW_LINES;
            JBLabel toggle = new JBLabel(expanded[0] ? "收起" : "...... +" + hidden + " lines");
            toggle.setFont(toggle.getFont().deriveFont(Font.ITALIC, 10f));
            toggle.setForeground(new JBColor(new Color(3, 102, 214), new Color(88, 166, 255)));
            toggle.setBorder(JBUI.Borders.empty(4, 4, 0, 4));
            toggle.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            toggle.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    expanded[0] = !expanded[0];
                    refresh[0].run();
                }
            });
            diffPanel.add(toggle);

            diffPanel.revalidate();
            diffPanel.repaint();
        };

        refresh[0].run();

        return diffPanel;
    }

    private static JComponent createDiffLineComponent(DiffLine line) {
        JTextArea textArea = new JTextArea(line.text()) {
            @Override
            public Dimension getPreferredSize() {
                int availableWidth = getParent() != null ? getParent().getWidth() - JBUI.scale(2) : 0;
                if (availableWidth <= 0) {
                    return super.getPreferredSize();
                }
                setSize(new Dimension(availableWidth, Short.MAX_VALUE));
                Dimension preferred = super.getPreferredSize();
                return new Dimension(availableWidth, preferred.height);
            }
        };
        textArea.setFont(MONO_FONT);
        textArea.setOpaque(true);
        textArea.setEditable(false);
        textArea.setFocusable(false);
        textArea.setLineWrap(true);
        textArea.setWrapStyleWord(false);
        textArea.setBorder(JBUI.Borders.empty(1, 6));
        textArea.setAlignmentX(LEFT_ALIGNMENT);
        textArea.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));

        switch (line.type()) {
            case ADDED -> {
                textArea.setBackground(BG_ADDED);
                textArea.setForeground(FG_ADDED);
            }
            case REMOVED -> {
                textArea.setBackground(BG_REMOVED);
                textArea.setForeground(FG_REMOVED);
            }
            default -> textArea.setOpaque(false);
        }
        return textArea;
    }

    // ==================== Action buttons ====================

    private JPanel buildActions() {
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 4));
        actions.setBorder(JBUI.Borders.empty(0, 6, 6, 6));
        actions.setOpaque(false);

        JButton locateBtn = smallButton("定位");
        JButton diffBtn = smallButton("差异");
        JButton applyBtn = smallButton("应用");
        JButton ignoreBtn = smallButton("忽略");

        applyBtn.setForeground(new JBColor(new Color(3, 102, 214), new Color(88, 166, 255)));

        locateBtn.addActionListener(e -> onLocate());
        diffBtn.addActionListener(e -> onShowDiff());
        applyBtn.addActionListener(e -> onApply());
        ignoreBtn.addActionListener(e -> onIgnore());

        actions.add(locateBtn);
        actions.add(diffBtn);
        actions.add(applyBtn);
        actions.add(ignoreBtn);
        return actions;
    }

    private static JButton smallButton(String text) {
        JButton btn = new JButton(text);
        btn.setFont(btn.getFont().deriveFont(11f));
        btn.putClientProperty("JButton.buttonType", "borderless");
        return btn;
    }

    // ==================== Card actions ====================

    private void onLocate() {
        PsiMethod method = MethodRecordUtil.resolveMethod(project, record);
        if (method != null) {
            method.navigate(true);
        } else {
            Messages.showWarningDialog(project, "无法定位方法：方法不存在", "定位失败");
        }
    }

    private void onShowDiff() {
        String oldText = record.getOldComment() != null ? record.getOldComment() : "";
        String newText = record.getStagedComment() != null ? record.getStagedComment() : "";

        DiffContentFactory contentFactory = DiffContentFactory.getInstance();
        SimpleDiffRequest request = new SimpleDiffRequest(
                "注释差异: " + record.getSignature(),
                contentFactory.create(oldText),
                contentFactory.create(newText),
                "旧注释", "新注释"
        );
        DiffManager.getInstance().showDiff(project, request);
    }

    private void onApply() {
        WriteCommandAction.runWriteCommandAction(project, () -> {
            PsiMethod method = MethodRecordUtil.resolveMethod(project, record);
            if (method == null) {
                Messages.showWarningDialog(project, "无法应用注释：方法不存在", "应用失败");
                return;
            }

            PsiElementFactory factory = JavaPsiFacade.getElementFactory(project);
            PsiDocComment newComment = factory.createDocCommentFromText(record.getStagedComment());
            PsiDocComment oldComment = method.getDocComment();
            if (oldComment != null && oldComment.isValid()) {
                oldComment.replace(newComment);
            } else {
                method.addBefore(newComment, method.getFirstChild());
            }

            record.copyStagedToOldMethod();
            record.copyStagedToOldComment();
            record.clearStagedComment();
            record.setStatus(MethodStatus.UNCHANGED);
            historyManager.save(record);
        });
    }

    private void onIgnore() {
        if (MethodStatus.TO_BE_GENERATE.equals(record.getStatus())) {
            record.copyStagedToOldMethod();
            record.clearStagedComment();
            record.setStatus(MethodStatus.NEW_METHOD_WITHOUT_COMMENT);
        } else if (MethodStatus.TO_BE_UPDATE.equals(record.getStatus())) {
            record.copyStagedToOldMethod();
            record.clearStagedComment();
            record.setStatus(MethodStatus.UNCHANGED);
        }
        historyManager.save(record);
        requestGutterRefresh();
    }

    /**
     * 通知 gutter 图标刷新，确保状态变更后图标即时更新。
     */
    private void requestGutterRefresh() {
        PsiFile psiFile = MethodRecordUtil.resolvePsiFile(project, record.getFilePath());
        if (psiFile == null || !psiFile.isValid()) return;
        project.getService(PluginProjectService.class).requestGutterIconRefresh(psiFile);
    }

    // ==================== LCS-based diff ====================

    /**
     * 基于 LCS 的统一 diff 算法，生成红绿 diff 行列表。
     */
    static List<DiffLine> computeDiff(String oldText, String newText) {
        String[] oldLines = splitLines(oldText);
        String[] newLines = splitLines(newText);

        int m = oldLines.length, n = newLines.length;
        int[][] dp = new int[m + 1][n + 1];
        for (int i = m - 1; i >= 0; i--) {
            for (int j = n - 1; j >= 0; j--) {
                if (oldLines[i].trim().equals(newLines[j].trim())) {
                    dp[i][j] = dp[i + 1][j + 1] + 1;
                } else {
                    dp[i][j] = Math.max(dp[i + 1][j], dp[i][j + 1]);
                }
            }
        }

        List<DiffLine> result = new ArrayList<>();
        int i = 0, j = 0;
        while (i < m || j < n) {
            if (i < m && j < n && oldLines[i].trim().equals(newLines[j].trim())) {
                result.add(new DiffLine(DiffType.CONTEXT, "  " + oldLines[i]));
                i++;
                j++;
            } else if (j < n && (i >= m || dp[i][j + 1] >= dp[i + 1][j])) {
                result.add(new DiffLine(DiffType.ADDED, "+ " + newLines[j]));
                j++;
            } else {
                result.add(new DiffLine(DiffType.REMOVED, "- " + oldLines[i]));
                i++;
            }
        }
        return result;
    }

    private static String[] splitLines(String text) {
        if (text == null || text.isEmpty()) return new String[0];
        return text.split("\n", -1);
    }

    enum DiffType {CONTEXT, ADDED, REMOVED}

    record DiffLine(DiffType type, String text) {}
}
