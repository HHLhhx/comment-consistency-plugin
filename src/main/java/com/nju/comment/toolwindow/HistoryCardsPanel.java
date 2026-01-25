package com.nju.comment.toolwindow;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiMethod;
import com.intellij.ui.components.JBScrollPane;
import com.nju.comment.dto.MethodRecord;
import com.nju.comment.history.MethodHistoryManager;
import com.nju.comment.history.MethodHistoryRepositoryImpl;
import com.nju.comment.service.PluginProjectService;
import lombok.Getter;

import javax.swing.*;
import java.awt.*;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.stream.Collectors;

public class HistoryCardsPanel {

    @Getter
    private final JPanel root;
    private final JPanel listPanel;
    private final JToggleButton autoDeleteBtn;

    private final Project project;
    private final MethodHistoryRepositoryImpl repository = MethodHistoryRepositoryImpl.getInstance();
    private final MethodHistoryManager methodHistoryManager = new MethodHistoryManager(repository);

    private ScheduledExecutorService autoDeleteScheduler;
    private ScheduledFuture<?> autoDeleteFuture;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private Set<String> lastSeenSignatures = ConcurrentHashMap.newKeySet();

    public HistoryCardsPanel(Project project) {
        this.project = project;
        root = new JPanel(new BorderLayout());

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT));
        autoDeleteBtn = new JToggleButton("Auto Delete: OFF");
        top.add(autoDeleteBtn);

        listPanel = new JPanel();
        listPanel.setLayout(new BoxLayout(listPanel, BoxLayout.Y_AXIS));
        JBScrollPane scrollPane = new JBScrollPane(listPanel);
        scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);

        root.add(top, BorderLayout.NORTH);
        root.add(scrollPane, BorderLayout.CENTER);

        autoDeleteBtn.addActionListener(e -> autoDelete(project));

        // 启动定时轮询，检测tag = 1的记录
        scheduler.scheduleWithFixedDelay(this::pollAndRefresh, 0, 500, TimeUnit.MILLISECONDS);
    }

    private void autoDelete(Project project) {
        if (autoDeleteBtn.isSelected()) {
            autoDeleteBtn.setText("Auto Delete: ON");
            if (autoDeleteScheduler == null || autoDeleteScheduler.isShutdown()) {
                autoDeleteScheduler = Executors.newSingleThreadScheduledExecutor();
            }
            autoDeleteFuture = autoDeleteScheduler.scheduleWithFixedDelay(() -> {
                        PluginProjectService service = project.getService(PluginProjectService.class);
                        List<PsiMethod> methods = service.collectAllMethods(project);
                        methodHistoryManager.clearDeletedMethodHistories(methods);
                    }, 3, 3, TimeUnit.SECONDS);
        } else {
            autoDeleteBtn.setText("Auto Delete: OFF");
            if (autoDeleteFuture != null) {
                autoDeleteFuture.cancel(true);
                autoDeleteFuture = null;
            }
            if (autoDeleteScheduler != null) {
                autoDeleteScheduler.shutdown();
                autoDeleteScheduler = null;
            }
        }
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
