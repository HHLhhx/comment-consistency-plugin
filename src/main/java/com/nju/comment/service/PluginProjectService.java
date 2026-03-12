package com.nju.comment.service;

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.nju.comment.client.global.CommentGeneratorClient;
import com.nju.comment.constant.Constant;
import com.nju.comment.history.MethodHistoryManager;
import com.nju.comment.history.MethodHistoryRepositoryImpl;
import com.nju.comment.pojo.MethodStatus;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.concurrent.*;

/**
 * 项目级核心服务：管理用户生命周期、自动化调度与 Gutter 图标去抖刷新。
 * <p>
 * 方法级的刷新 / 生成 / 变更检测逻辑委托给 {@link MethodRefreshService}。
 */
@Slf4j
@Service(Service.Level.PROJECT)
public final class PluginProjectService implements Disposable {

    private static final String DEFAULT_BASE_URL = Constant.CLIENT_DEFAULT_BASE_URL;

    private final Project project;
    private final MethodHistoryRepositoryImpl history;

    @Getter
    private final MethodHistoryManager methodHistoryManager;

    @Getter
    private final MethodRefreshService methodRefreshService;

    @Getter
    private final CompletableFuture<Void> initializationFuture = new CompletableFuture<>();

    /** 去抖：每个文件对应一个延迟 restart 任务，新变更重置计时 */
    private final ConcurrentHashMap<String, ScheduledFuture<?>> pendingRefreshes = new ConcurrentHashMap<>();

    /** 保护 autoScheduler / autoUpdateTask / autoCleanTask / userSettings 的锁 */
    private final Object scheduleLock = new Object();

    @Getter
    private volatile UserSettingsManager userSettings;

    private ScheduledExecutorService autoScheduler;
    private ScheduledFuture<?> autoUpdateTask;
    private ScheduledFuture<?> autoCleanTask;

    /** UI 层注册的强制登出回调（切换到登录界面） */
    @Setter
    private volatile Runnable onForceLogoutCallback;

    public PluginProjectService(Project project) {
        this.project = project;
        this.history = new MethodHistoryRepositoryImpl();
        this.methodHistoryManager = new MethodHistoryManager(history, project);
        this.methodRefreshService = new MethodRefreshService(
                project, methodHistoryManager,
                this::isAutoUpdateEnabled,
                this::requestGutterIconRefresh);
    }

    // ==================== 自动化调度 ====================

    public void setAutoUpdateEnabled(boolean enabled) {
        synchronized (scheduleLock) {
            setAutoUpdateEnabledLocked(enabled);
        }
    }

    public boolean isAutoUpdateEnabled() {
        synchronized (scheduleLock) {
            return autoUpdateTask != null && !autoUpdateTask.isCancelled();
        }
    }

    public void setAutoCleanEnabled(boolean enabled) {
        synchronized (scheduleLock) {
            setAutoCleanEnabledLocked(enabled);
        }
    }

    /** 必须在持有 scheduleLock 时调用 */
    private void ensureSchedulerLocked() {
        if (autoScheduler == null || autoScheduler.isShutdown()) {
            autoScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "plugin-auto-scheduler");
                t.setDaemon(true);
                return t;
            });
        }
    }

    /** 不加锁版本，供已持有 scheduleLock 的方法内部使用 */
    private void setAutoUpdateEnabledLocked(boolean enabled) {
        if (enabled) {
            ensureSchedulerLocked();
            if (autoUpdateTask == null || autoUpdateTask.isCancelled()) {
                autoUpdateTask = autoScheduler.scheduleWithFixedDelay(
                        methodRefreshService::refreshAll,
                        Constant.AUTO_UPDATE_INITIAL_DELAY_MS,
                        Constant.AUTO_UPDATE_DELAY_MS, TimeUnit.MILLISECONDS);
            }
        } else if (autoUpdateTask != null) {
            autoUpdateTask.cancel(false);
            autoUpdateTask = null;
        }
    }

    /** 不加锁版本，供已持有 scheduleLock 的方法内部使用 */
    private void setAutoCleanEnabledLocked(boolean enabled) {
        if (enabled) {
            ensureSchedulerLocked();
            if (autoCleanTask == null || autoCleanTask.isCancelled()) {
                autoCleanTask = autoScheduler.scheduleWithFixedDelay(
                        () -> DumbService.getInstance(project).runWhenSmart(() ->
                                ApplicationManager.getApplication().executeOnPooledThread(() -> {
                                    List<PsiMethod> methods = methodRefreshService.collectAllMethods();
                                    methodHistoryManager.clearDeletedMethodHistories(methods);
                                })),
                        Constant.AUTO_DELETE_INITIAL_DELAY_MS,
                        Constant.AUTO_DELETE_DELAY_MS, TimeUnit.MILLISECONDS);
            }
        } else if (autoCleanTask != null) {
            autoCleanTask.cancel(false);
            autoCleanTask = null;
        }
    }

    // ==================== Gutter 图标刷新 ====================

    /**
     * 监听 Java 文件 PSI 变化，通过去抖延迟触发 DaemonCodeAnalyzer.restart，
     * 解决编辑注释时 gutter 图标不刷新的问题。
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
     * 去抖调度：取消同文件的前序待执行 restart，重新延迟后执行。
     */
    private void scheduleGutterRefresh(PsiFile file) {
        String path = file.getVirtualFile().getPath();

        ScheduledFuture<?> prev = pendingRefreshes.get(path);
        if (prev != null) prev.cancel(false);

        synchronized (scheduleLock) {
            ensureSchedulerLocked();
            ScheduledFuture<?> task = autoScheduler.schedule(() ->
                    ApplicationManager.getApplication().invokeLater(() -> {
                        pendingRefreshes.remove(path);
                        if (file.isValid()) {
                            DaemonCodeAnalyzer.getInstance(project).restart(file);
                        }
                    }), Constant.GUTTER_REFRESH_DEBOUNCE_MS, TimeUnit.MILLISECONDS);
            pendingRefreshes.put(path, task);
        }
    }

    /**
     * 请求刷新指定文件的 gutter 图标（去抖），供后台线程状态变更后调用。
     */
    public void requestGutterIconRefresh(PsiFile file) {
        if (file == null || !file.isValid()) return;
        scheduleGutterRefresh(file);
    }

    // ==================== 生命周期 ====================

    /**
     * 项目启动时初始化
     */
    public void initialize() {
        log.info("项目启动初始化");
        AuthManager.init();
        CommentGeneratorClient.init(DEFAULT_BASE_URL);
        registerGutterRefreshListener();

        if (AuthManager.isLoggedIn()) {
            onUserLogin();
            ApplicationManager.getApplication().executeOnPooledThread(() -> {
                CommentGeneratorClient.getAvailableModels(project);
                initializationFuture.complete(null);
            });
        } else {
            initializationFuture.complete(null);
        }
    }

    /**
     * 用户登录后调用：恢复持久化设置，启动自动化任务。
     */
    public void onUserLogin() {
        String username = AuthManager.getUsername();
        synchronized (scheduleLock) {
            this.userSettings = new UserSettingsManager(project, username);
            history.clear();

            String model = userSettings.getSelectedModel();
            if (model != null) CommentGeneratorClient.setSelectedModel(model);
            CommentGeneratorClient.setRagEnabled(userSettings.isRagEnabled());

            setAutoUpdateEnabledLocked(userSettings.isAutoUpdateEnabled());
            setAutoCleanEnabledLocked(true);
        }
        log.info("用户 {} 登录，已恢复设置", username);
    }

    /**
     * 用户登出时调用：保存设置，停止任务，清除数据。
     */
    public void onUserLogout() {
        synchronized (scheduleLock) {
            saveCurrentSettingsLocked();
            setAutoUpdateEnabledLocked(false);
            setAutoCleanEnabledLocked(false);
            history.clear();
            userSettings = null;
        }
        log.info("用户已登出，已清理资源");
    }

    /**
     * 服务端判定凭证失效时调用：执行登出 + 切换到登录界面。
     */
    public void forceLogout() {
        synchronized (scheduleLock) {
            saveCurrentSettingsLocked();
            setAutoUpdateEnabledLocked(false);
            setAutoCleanEnabledLocked(false);
            history.clear();
            userSettings = null;
        }
        AuthManager.clearAuth();
        Runnable cb = onForceLogoutCallback;
        if (cb != null) {
            ApplicationManager.getApplication().invokeLater(cb);
        }
        log.info("强制登出完成");
    }

    /**
     * 保存当前运行时设置到持久化存储。
     */
    public void saveCurrentSettings() {
        synchronized (scheduleLock) {
            saveCurrentSettingsLocked();
        }
    }

    /** 必须在持有 scheduleLock 时调用 */
    private void saveCurrentSettingsLocked() {
        if (userSettings == null) return;
        userSettings.setSelectedModel(CommentGeneratorClient.getSelectedModel());
        userSettings.setRagEnabled(CommentGeneratorClient.isRagEnabled());
        userSettings.setAutoUpdateEnabled(autoUpdateTask != null && !autoUpdateTask.isCancelled());
    }

    // ==================== 委托方法 ====================

    public void refreshAllMethodHistories() {
        methodRefreshService.refreshAll();
    }

    public void refreshFileMethodHistories(VirtualFile file) {
        methodRefreshService.refreshFile(file);
    }

    public void refreshMethodHistory(PsiMethod method) {
        methodRefreshService.refreshMethod(method);
    }

    public void generateComment(PsiMethod method) {
        methodRefreshService.generateComment(method);
    }

    public MethodStatus preCheckChange(PsiMethod method) {
        return methodRefreshService.preCheckChange(method);
    }

    // ==================== 资源释放 ====================

    @Override
    public void dispose() {
        log.info("项目关闭，释放资源");
        synchronized (scheduleLock) {
            saveCurrentSettingsLocked();
            setAutoUpdateEnabledLocked(false);
            setAutoCleanEnabledLocked(false);
            if (autoScheduler != null) {
                autoScheduler.shutdownNow();
                autoScheduler = null;
            }
        }
        // 取消所有待执行的 gutter 去抖任务
        pendingRefreshes.values().forEach(f -> f.cancel(false));
        pendingRefreshes.clear();
    }
}
