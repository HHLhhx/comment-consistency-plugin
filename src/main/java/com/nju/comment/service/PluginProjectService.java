package com.nju.comment.service;

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.javadoc.PsiDocComment;
import com.intellij.psi.search.FilenameIndex;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.PsiTreeUtil;
import com.nju.comment.client.global.AuthManager;
import com.nju.comment.client.global.CommentGeneratorClient;
import com.nju.comment.constant.Constant;
import com.nju.comment.pojo.GenerateOptions;
import com.nju.comment.pojo.MethodStatus;
import com.nju.comment.dto.request.CommentReqTag;
import com.nju.comment.history.MethodHistoryManager;
import com.nju.comment.history.MethodHistoryRepositoryImpl;
import com.nju.comment.util.TextProcessUtil;
import com.nju.comment.util.MethodRecordUtil;
import com.nju.comment.pojo.MethodRecord;
import com.nju.comment.util.MethodValidationUtil;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.*;

@Slf4j
@Service(Service.Level.PROJECT)
public final class PluginProjectService implements Disposable {

    private static final String DEFAULT_BASE_URL = Constant.CLIENT_DEFAULT_BASE_URL;
    private static final long GUTTER_REFRESH_DEBOUNCE_MS = 500;

    private final Project project;
    private final MethodHistoryManager methodHistoryManager;
    /** 去抖：每个文件对应一个延迟 restart 任务，新变更重置计时 */
    private final ConcurrentHashMap<String, ScheduledFuture<?>> pendingRefreshes = new ConcurrentHashMap<>();

    @Getter
    private final CompletableFuture<Void> initializationFuture = new CompletableFuture<>();

    private ScheduledExecutorService autoScheduler;
    private ScheduledFuture<?> autoUpdateTask;
    private ScheduledFuture<?> autoCleanTask;

    public PluginProjectService(Project project) {
        this.project = project;
        this.methodHistoryManager = new MethodHistoryManager(MethodHistoryRepositoryImpl.getInstance());
    }

    // ==================== 自动化调度 ====================

    public void setAutoUpdateEnabled(boolean enabled) {
        if (enabled) {
            ensureScheduler();
            if (autoUpdateTask == null || autoUpdateTask.isCancelled()) {
                autoUpdateTask = autoScheduler.scheduleWithFixedDelay(
                        this::refreshAllMethodHistories,
                        Constant.AUTO_UPDATE_INITIAL_DELAY_MS,
                        Constant.AUTO_UPDATE_DELAY_MS, TimeUnit.MILLISECONDS);
            }
        } else if (autoUpdateTask != null) {
            autoUpdateTask.cancel(false);
            autoUpdateTask = null;
        }
    }

    public boolean isAutoUpdateEnabled() {
        return autoUpdateTask != null && !autoUpdateTask.isCancelled();
    }

    public void setAutoCleanEnabled(boolean enabled) {
        if (enabled) {
            ensureScheduler();
            if (autoCleanTask == null || autoCleanTask.isCancelled()) {
                autoCleanTask = autoScheduler.scheduleWithFixedDelay(
                        () -> {
                            List<PsiMethod> methods = collectAllMethods(project);
                            methodHistoryManager.clearDeletedMethodHistories(methods);
                        },
                        Constant.AUTO_DELETE_INITIAL_DELAY_MS,
                        Constant.AUTO_DELETE_DELAY_MS, TimeUnit.MILLISECONDS);
            }
        } else if (autoCleanTask != null) {
            autoCleanTask.cancel(false);
            autoCleanTask = null;
        }
    }

    private void ensureScheduler() {
        if (autoScheduler == null || autoScheduler.isShutdown()) {
            autoScheduler = Executors.newSingleThreadScheduledExecutor();
        }
    }

    // ==================== Gutter 图标刷新 ====================

    /**
     * 监听 Java 文件 PSI 变化，通过去抖延迟触发 DaemonCodeAnalyzer.restart，
     * 解决编辑注释时 gutter 图标不刷新的问题。
     * <p>
     * 去抖策略：同一文件连续变更只在最后一次变更后 {@value GUTTER_REFRESH_DEBOUNCE_MS}ms 才真正 restart，
     * 避免每次击键都触发全量重新高亮。
     */
    private void registerGutterRefreshListener() {
        PsiManager.getInstance(project).addPsiTreeChangeListener(new PsiTreeChangeAdapter() {
            @Override
            public void childrenChanged(@NotNull PsiTreeChangeEvent event) {
                PsiFile file = event.getFile();
                if (file == null) return;
                VirtualFile vf = file.getVirtualFile();
                if (vf == null || !"java".equalsIgnoreCase(vf.getExtension())) return;
                scheduleGutterRefresh(file);
            }
        }, this);
    }

    /**
     * 去抖调度：取消同文件的前序待执行 restart，重新延迟 {@value GUTTER_REFRESH_DEBOUNCE_MS}ms 后执行。
     */
    private void scheduleGutterRefresh(PsiFile file) {
        String path = file.getVirtualFile().getPath();
        ensureScheduler();

        ScheduledFuture<?> prev = pendingRefreshes.get(path);
        if (prev != null) prev.cancel(false);

        ScheduledFuture<?> task = autoScheduler.schedule(() ->
                ApplicationManager.getApplication().invokeLater(() -> {
                    pendingRefreshes.remove(path);
                    if (file.isValid()) {
                        DaemonCodeAnalyzer.getInstance(project).restart(file);
                    }
                }), GUTTER_REFRESH_DEBOUNCE_MS, TimeUnit.MILLISECONDS);
        pendingRefreshes.put(path, task);
    }

    /**
     * 请求刷新指定文件的 gutter 图标（去抖），供后台线程状态变更后调用。
     */
    public void requestGutterIconRefresh(PsiFile file) {
        if (file == null || !file.isValid()) return;
        scheduleGutterRefresh(file);
    }

    // ==================== 项目服务 ====================

    /**
     * 项目启动时初始化
     */
    public void initialize() {
        log.info("项目启动初始化");
        AuthManager.init();
        CommentGeneratorClient.init(DEFAULT_BASE_URL);
        setAutoCleanEnabled(true);
        registerGutterRefreshListener();

        if (AuthManager.isLoggedIn()) {
            ApplicationManager.getApplication().executeOnPooledThread(() -> {
                CommentGeneratorClient.getAvailableModels();
                initializationFuture.complete(null);
            });
        } else {
            // 未登录时直接完成初始化，等待用户在工具窗口中登录
            initializationFuture.complete(null);
        }
    }

    /**
     * 刷新项目中所有方法历史记录
     */
    public void refreshAllMethodHistories() {
        ApplicationManager.getApplication().executeOnPooledThread(this::doRefreshAllMethodHistories);
    }

    /**
     * 刷新项目中所有方法历史记录的具体实现
     */
    private void doRefreshAllMethodHistories() {
        log.info("刷新项目中所有方法历史记录");
        List<PsiMethod> methods = collectAllMethods(project);
        log.info("共找到方法数量：{}", methods.size());

        for (PsiMethod method : methods) {
            ApplicationManager.getApplication()
                    .executeOnPooledThread(() -> doRefreshMethodHistory(method));
        }
    }

    /**
     * 收集项目中所有方法
     *
     * @param project 当前项目
     * @return 方法列表
     */
    public List<PsiMethod> collectAllMethods(Project project) {
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

    /**
     * 刷新单文件中所有方法历史记录
     *
     * @param file 目标文件
     */
    public void refreshFileMethodHistories(VirtualFile file) {
        ApplicationManager.getApplication().executeOnPooledThread(() -> doRefreshFileMethodHistories(file));
    }

    /**
     * 刷新单文件中所有方法历史记录的具体实现
     *
     * @param file 目标文件
     */
    private void doRefreshFileMethodHistories(VirtualFile file) {
        if (file == null || !file.exists() || !"java".equalsIgnoreCase(file.getExtension())) {
            log.warn("文件无效，无法刷新方法历史记录: {}", file);
            return;
        }

        log.info("刷新文件方法历史记录，path: {}", file.getPath());
        List<PsiMethod> methods = ReadAction.compute(() -> {
            PsiManager psiManager = PsiManager.getInstance(project);
            PsiFile psiFile = psiManager.findFile(file);
            if (psiFile == null) return List.of();

            Collection<PsiMethod> coll = PsiTreeUtil.collectElementsOfType(psiFile, PsiMethod.class);
            return new ArrayList<>(coll);
        });
        log.info("文件中找到方法数量：{}", methods.size());

        for (PsiMethod method : methods) {
            ApplicationManager.getApplication()
                    .executeOnPooledThread(() -> doRefreshMethodHistory(method));
        }
    }

    /**
     * 刷新单方法历史记录
     *
     * @param method 目标方法
     */
    public void refreshMethodHistory(PsiMethod method) {
        ApplicationManager.getApplication().executeOnPooledThread(() -> doRefreshMethodHistory(method));
    }

    /**
     * 刷新单方法历史。自动周期更新与手动（项目/文件/方法）更新统一由此执行；
     * 同一方法下「重复触发以最初为准、修改后再触发以最近为准」由 CommentGeneratorClient 按内容指纹保证。
     */
    private void doRefreshMethodHistory(PsiMethod method) {
        ReadAction.run(() -> {
            if (!MethodValidationUtil.isValid(method)) return;

            PsiFile psiFile = method.getContainingFile();
            String methodKey = MethodRecordUtil.buildMethodKey(method);
            try {
                methodHistoryManager.updateMethodHistoryAsync(method, (context, status) -> {
                    // 根据状态选择生成标签
                    CommentReqTag tag;
                    if (MethodStatus.TO_BE_GENERATE.equals(status)) {
                        tag = CommentReqTag.GENERATE;
                    } else {
                        tag = CommentGeneratorClient.isRagEnabled()
                                ? CommentReqTag.UPDATE_WITH_RAG
                                : CommentReqTag.UPDATE_WITHOUT_RAG;
                    }
                    GenerateOptions options = GenerateOptions.builder()
                            .modelName(CommentGeneratorClient.getSelectedModel())
                            .tag(tag)
                            .build();

                    // 使用异步回调方式生成注释，不阻塞UI线程
                    CommentGeneratorClient.generateCommentAsync(methodKey, context, options, generatedComment -> {
                        if (generatedComment == null) {
                            return;
                        }
                        String processedComment = TextProcessUtil.processComment(generatedComment);

                        // 在后台线程中更新历史记录
                        ApplicationManager.getApplication().executeOnPooledThread(() -> {
                            MethodRecord record = methodHistoryManager.findByKey(methodKey);
                            if (record != null) {
                                record.setStagedComment(processedComment);
                                if (status.equals(MethodStatus.TO_BE_UPDATE)) {
                                    // 更新为待更新状态
                                    record.setStatus(MethodStatus.TO_BE_UPDATE);
                                } else if (status.equals(MethodStatus.TO_BE_GENERATE)) {
                                    // 更新为待生成状态
                                    record.setStatus(MethodStatus.TO_BE_GENERATE);
                                }
                                record.touch();
                                methodHistoryManager.save(record);
                                requestGutterIconRefresh(psiFile);
                            }
                        });
                    });
                }, isAutoUpdateEnabled());
                requestGutterIconRefresh(psiFile);
            } catch (Exception ex) {
                log.warn("刷新方法历史记录失败，方法签名：{}", methodKey, ex);
            }
        });
    }

    /**
     * 生成方法注释
     *
     * @param method 目标方法
     */
    public void generateComment(PsiMethod method) {
        if (method == null) {
            log.warn("方法为空，无法生成注释");
            return;
        }

        String methodKey = MethodRecordUtil.buildMethodKey(method);
        if (!MethodStatus.NEW_METHOD_WITHOUT_COMMENT.equals(preCheckChange(method))) {
            log.info("方法不处于可生成注释状态，跳过生成：{}", methodKey);
            return;
        }

        doRefreshMethodHistory(method);

        MethodRecord record = methodHistoryManager.findByKey(methodKey);
        if (!MethodStatus.NEW_METHOD_WITHOUT_COMMENT.equals(record.getStatus())) {
            log.info("方法不处于可生成注释状态，跳过生成：{}", methodKey);
            return;
        }

        record.setStatus(MethodStatus.GENERATING);
        record.touch();
        methodHistoryManager.save(record);
        refreshMethodHistory(method);
    }

    /**
     * 检查方法变更类型
     *
     * @param method 目标方法
     * @return 变更类型
     */
    public MethodStatus preCheckChange(PsiMethod method) {
        if (method == null) {
            log.warn("方法为空，无法检查变更");
            return null;
        }

        String methodKey = MethodRecordUtil.buildMethodKey(method);
        MethodRecord record = methodHistoryManager.findByKey(methodKey);

        String curComment = ReadAction.compute(() -> {
            PsiDocComment pdc = method.getDocComment();
            return pdc != null ? pdc.getText().trim() : "";
        });
        String curMethod = MethodRecordUtil.getMethodTextWithoutComments(method);

        curComment = TextProcessUtil.processComment(curComment);
        curMethod = TextProcessUtil.processMethod(curMethod);

        if (record == null) {
            return curComment.isEmpty() ? MethodStatus.NEW_METHOD_WITHOUT_COMMENT : MethodStatus.NEW_METHOD_WITH_COMMENT;
        } else if (MethodStatus.NEW_METHOD_WITHOUT_COMMENT.equals(record.getStatus())
                || MethodStatus.NEW_METHOD_WITH_COMMENT.equals(record.getStatus())) {
            return record.getStatus();
        }

        String oldComment = record.getOldComment();
        String oldMethod = record.getOldMethod();

        boolean commentChanged = !oldComment.equals(curComment);
        boolean methodChanged = !oldMethod.equals(curMethod);

        if (commentChanged) {
            return MethodStatus.COMMENT_CHANGED;
        } else if (methodChanged) {
            return MethodStatus.METHOD_CHANGED;
        }

        return MethodStatus.UNCHANGED;
    }

    /**
     * 项目关闭时释放资源
     */
    @Override
    public void dispose() {
        log.info("项目关闭，释放资源");
        setAutoUpdateEnabled(false);
        setAutoCleanEnabled(false);
        if (autoScheduler != null) {
            autoScheduler.shutdownNow();
        }
        CommentGeneratorClient.shutdown();
    }
}
