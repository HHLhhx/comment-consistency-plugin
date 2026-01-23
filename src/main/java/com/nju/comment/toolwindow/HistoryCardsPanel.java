package com.nju.comment.toolwindow;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.ui.components.JBScrollPane;
import com.nju.comment.dto.MethodRecord;
import com.nju.comment.history.MethodHistoryRepositoryImpl;
import lombok.Getter;

import javax.swing.*;
import java.awt.*;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class HistoryCardsPanel {

    @Getter
    private final JPanel root;
    private final JPanel listPanel;
    private final JBScrollPane scrollPane;
    private final Project project;
    private final MethodHistoryRepositoryImpl repository = MethodHistoryRepositoryImpl.getInstance();

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private Set<String> lastSeenSignatures = ConcurrentHashMap.newKeySet();

    public HistoryCardsPanel(Project project) {
        this.project = project;
        root = new JPanel(new BorderLayout());
        listPanel = new JPanel();
        listPanel.setLayout(new BoxLayout(listPanel, BoxLayout.Y_AXIS));
        scrollPane = new JBScrollPane(listPanel);
        scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        root.add(scrollPane, BorderLayout.CENTER);

        // 启动定时轮询，检测tag = 1的记录
        scheduler.scheduleWithFixedDelay(this::pollAndRefresh, 0, 1, TimeUnit.SECONDS);
    }

    private void pollAndRefresh() {
        List<MethodRecord> staged = repository.findAll().stream()
                .filter(r -> r.getTag() == 1)
                .toList();
        Set<String> current = staged.stream().map(MethodRecord::getSignature).collect(Collectors.toSet());
        if (!current.equals(lastSeenSignatures)) {
            lastSeenSignatures = current;
            ApplicationManager.getApplication().invokeLater(() -> refreshList(staged));
        }
    }

    private void refreshList(List<MethodRecord> staged) {
        listPanel.removeAll();
        for (MethodRecord record : staged) {
            MethodHistoryCard card = new MethodHistoryCard(project, record, repository, () -> {
                listPanel.revalidate();
                listPanel.repaint();
            });
            listPanel.add(card.getRoot());
            listPanel.add(Box.createVerticalStrut(10));
        }
        listPanel.revalidate();
        listPanel.repaint();
    }
}
