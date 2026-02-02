package com.nju.comment.toolwindow;

import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiElementFactory;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.SmartPsiElementPointer;
import com.intellij.psi.javadoc.PsiDocComment;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;
import com.nju.comment.dto.MethodRecord;
import com.nju.comment.dto.MethodStatus;
import com.nju.comment.history.MethodHistoryManager;
import com.nju.comment.history.MethodHistoryRepositoryImpl;
import lombok.Getter;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.util.Arrays;
import java.util.stream.Collectors;

public class MethodHistoryCard {

    @Getter
    private final JPanel root;
    private final MethodRecord record;
    private final Project project;
    private final MethodHistoryManager methodHistoryManager =
            new MethodHistoryManager(MethodHistoryRepositoryImpl.getInstance());

    public MethodHistoryCard(Project project, MethodRecord record) {
        this.project = project;
        this.record = record;
        this.root = buildCard();
    }

    private JPanel buildCard() {
        JPanel p = new JPanel(new BorderLayout());
        p.setBorder(BorderFactory.createLineBorder(JBColor.LIGHT_GRAY));
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, 250));
        p.setPreferredSize(new Dimension(400, 250));
        p.setBackground(JBColor.WHITE);

        // Header: signature
        JLabel sig = new JLabel(record.getSignature());
        sig.setBorder(JBUI.Borders.empty(6));
        p.add(sig, BorderLayout.NORTH);

        // Center: show short preview of old and staged comments
        JPanel center = new JPanel(new GridLayout(1, 2));
        JTextArea oldArea = new JTextArea(safePreview(record.getOldComment()));
        oldArea.setEditable(false);
        oldArea.setBorder(BorderFactory.createTitledBorder("Old Comment"));
        JTextArea newArea = new JTextArea(safePreview(record.getStagedComment()));
        newArea.setEditable(false);
        newArea.setBorder(BorderFactory.createTitledBorder("New Comment"));
        center.add(new JScrollPane(oldArea));
        center.add(new JScrollPane(newArea));
        p.add(center, BorderLayout.CENTER);

        // Buttons
        JPanel btns = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton locate = new JButton("Locate");
        JButton details = new JButton("Diff");
        JButton apply = new JButton("Apply");
        JButton ignore = new JButton("Ignore");

        locate.addActionListener(this::onLocate);
        details.addActionListener(e -> onDiff());
        apply.addActionListener(e -> onApply());
        ignore.addActionListener(e -> onIgnore());

        btns.add(locate);
        btns.add(details);
        btns.add(apply);
        btns.add(ignore);
        p.add(btns, BorderLayout.SOUTH);

        return p;
    }

    private String safePreview(String s) {
        if (s == null) return "";
        String[] lines = s.split("\n");
        return Arrays.stream(lines).limit(8).collect(Collectors.joining("\n"));
    }

    private void onLocate(ActionEvent e) {
        SmartPsiElementPointer<PsiMethod> pointer = record.getPointer();
        PsiMethod method = pointer.getElement();
        if (method != null) {
            method.navigate(true);
            return;
        }
        Messages.showWarningDialog(project, "Cannot locate the method: method not found", "Locate Error");
    }

    private void onDiff() {
        String oldComment = record.getOldComment() == null ? "" : record.getOldComment();
        String newComment = record.getStagedComment() == null ? "" : record.getStagedComment();

        String[] oldLines = oldComment.split("\n");
        String[] newLines = newComment.split("\n");
        StringBuilder html = new StringBuilder("<html><body style='font-family:monospace'>");
        int max = Math.max(oldLines.length, newLines.length);
        for (int i = 0; i < max; i++) {
            String o = i < oldLines.length ? htmlEscape(oldLines[i]) : "";
            String n = i < newLines.length ? htmlEscape(newLines[i]) : "";
            if (o.equals(n)) {
                html.append("<div>").append(o).append("</div>");
            } else {
                if (!o.isEmpty()) {
                    html.append("<div style='background:#ffecec;color:#c00;'>- ").append(o).append("</div>");
                }
                if (!n.isEmpty()) {
                    html.append("<div style='background:#eaffea;color:#0a0;'>+ ").append(n).append("</div>");
                }
            }
        }
        html.append("</body></html>");

        JEditorPane editorPane = new JEditorPane("text/html", html.toString());
        editorPane.setEditable(false);
        JBScrollPane scrollPane = new JBScrollPane(editorPane);
        scrollPane.setPreferredSize(new Dimension(700, 400));
        JOptionPane.showMessageDialog(null, scrollPane,
                "Comment Diff - " + record.getSignature(), JOptionPane.PLAIN_MESSAGE);
    }

    private String htmlEscape(String s) {
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private void onApply() {
        SmartPsiElementPointer<PsiMethod> pointer = record.getPointer();
        WriteCommandAction.runWriteCommandAction(project, () -> {
            PsiMethod method = pointer.getElement();
            if (method == null) {
                Messages.showWarningDialog(project, "Cannot apply the comment: method not found", "Apply Error");
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

            if (MethodStatus.TO_BE_GENERATE.equals(record.getStatus())) {
                record.copyStagedToOldMethod();
                record.copyStagedToOldComment();
                record.clearStagedComment();
                record.setStatus(MethodStatus.NEW_METHOD_WITH_COMMENT);
            } else if (MethodStatus.TO_BE_UPDATE.equals(record.getStatus())) {
                record.copyStagedToOldMethod();
                record.copyStagedToOldComment();
                record.clearStagedComment();
                record.setStatus(MethodStatus.UNCHANGED);
            }
            methodHistoryManager.save(record);
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
        methodHistoryManager.save(record);
    }
}
