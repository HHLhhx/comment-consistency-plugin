package com.nju.comment.service;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.search.FilenameIndex;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.PsiTreeUtil;
import com.nju.comment.client.global.CommentGeneratorClient;
import com.nju.comment.dto.GenerateOptions;
import com.nju.comment.history.MethodHistoryManager;
import com.nju.comment.history.MethodHistoryRepositoryImpl;
import com.nju.comment.util.CommentProcessUtil;
import com.nju.comment.util.MethodRecordUtil;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service(Service.Level.PROJECT)
public final class PluginProjectService implements Disposable {

    private static final String DEFAULT_BASE_URL = "http://localhost:8080/api";

    private final Project project;
    private final MethodHistoryManager methodHistoryManager;
    private final ScheduledExecutorService delScheduler = Executors.newSingleThreadScheduledExecutor();

    public PluginProjectService(Project project) {
        this.project = project;
        this.methodHistoryManager = new MethodHistoryManager(MethodHistoryRepositoryImpl.getInstance());
    }

    public void initialize() {
        log.info("项目启动初始化");
        CommentGeneratorClient.init(DEFAULT_BASE_URL);
        ApplicationManager.getApplication().executeOnPooledThread(CommentGeneratorClient::getAvailableModels);
        DumbService.getInstance(project).runWhenSmart(this::refreshAllMethodHistories);

        delScheduler.scheduleWithFixedDelay(() -> {
            List<PsiMethod> methods = collectAllMethods(project);
            methodHistoryManager.clearDeletedMethodHistories(methods);
        }, 3, 3, TimeUnit.SECONDS);
    }

    public void refreshAllMethodHistories() {
        Runnable task = () -> ReadAction.run(this::doRefreshAllMethodHistories);
        if (ApplicationManager.getApplication().isDispatchThread()) {
            ApplicationManager.getApplication().executeOnPooledThread(task);
        } else {
            task.run();
        }
    }

    private void doRefreshAllMethodHistories() {
        log.info("刷新项目中所有方法历史记录");
        List<PsiMethod> methods = collectAllMethods(project);
        for (PsiMethod method : methods) {
            doRefreshMethodHistory(method);
        }
        log.info("所有方法历史记录刷新完成");
    }

    private List<PsiMethod> collectAllMethods(Project project) {
        return ReadAction.compute(() -> {
            List<PsiMethod> result = new ArrayList<>();
            Collection<VirtualFile> files = FilenameIndex.getAllFilesByExt(project, "java", GlobalSearchScope.projectScope(project));
            PsiManager psiManager = PsiManager.getInstance(project);
            for (VirtualFile vf : files) {
                PsiFile psiFile = psiManager.findFile(vf);
                if (psiFile == null) continue;
                Collection<PsiMethod> methods = PsiTreeUtil.collectElementsOfType(psiFile, PsiMethod.class);
                result.addAll(methods);
            }
            return result;
        });
    }

    public void refreshFileMethodHistories(VirtualFile file) {
        if (file == null || !file.exists() || !"java".equalsIgnoreCase(file.getExtension())) return;
        ApplicationManager.getApplication().executeOnPooledThread(() -> doRefreshFileMethodHistories(file));
    }

    private void doRefreshFileMethodHistories(VirtualFile file) {
        log.info("刷新文件方法历史记录，path: {}", file.getPath());
        ReadAction.run(() -> {
            PsiManager psiManager = PsiManager.getInstance(project);
            PsiFile psiFile = psiManager.findFile(file);
            if (psiFile == null) return;

            Collection<PsiMethod> methods = PsiTreeUtil.collectElementsOfType(psiFile, PsiMethod.class);
            for (PsiMethod method : methods) {
                doRefreshMethodHistory(method);
            }
        });
        log.info("文件方法历史记录刷新完成，path: {}", file.getPath());
    }

    public void refreshMethodHistory(PsiMethod method) {
        if (method == null || !method.isValid()) return;
        ApplicationManager.getApplication().executeOnPooledThread(() -> doRefreshMethodHistory(method));
    }

    private void doRefreshMethodHistory(PsiMethod method) {
        ReadAction.run(() -> {
            try {
                GenerateOptions options = new GenerateOptions(CommentGeneratorClient.getSelectedModel());
                methodHistoryManager.updateMethodHistory(method,
                        context -> {
                            if (context.getOldMethod() == null || context.getOldMethod().isBlank()) {
                                return "TODO";
                            }
                            String generated = CommentGeneratorClient.generateComment(context, options);
                            return CommentProcessUtil.processComment(generated, method);
                        });
            } catch (Exception ex) {
                log.warn("刷新方法历史记录失败，方法签名：{}", MethodRecordUtil.buildMethodKey(method), ex);
            }
        });
    }

    @Override
    public void dispose() {
        log.info("项目关闭，释放资源");
        CommentGeneratorClient.shutdown();
    }
}
