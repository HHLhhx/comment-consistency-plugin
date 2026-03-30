package com.nju.comment.service;

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiTreeChangeAdapter;
import com.intellij.psi.PsiTreeChangeEvent;
import com.intellij.psi.PsiMethod;
import com.nju.comment.client.global.CommentGeneratorClient;
import com.nju.comment.constant.Constant;
import com.nju.comment.history.MethodHistoryManager;
import com.nju.comment.history.MethodHistoryRepositoryImpl;
import com.nju.comment.pojo.MethodStatus;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.Consumer;

@Slf4j
@Service(Service.Level.PROJECT)
public final class PluginProjectService implements Disposable {

    private static final String DEFAULT_BASE_URL = Constant.CLIENT_DEFAULT_BASE_URL;
    private static final long DIRTY_REFRESH_DEBOUNCE_MS = 400L;

    private final Project project;
    private final MethodHistoryRepositoryImpl history;

    @Getter
    private final MethodHistoryManager methodHistoryManager;

    @Getter
    private final MethodRefreshService methodRefreshService;

    @Getter
    private final CompletableFuture<Void> initializationFuture = new CompletableFuture<>();

    private final ConcurrentHashMap<String, ScheduledFuture<?>> pendingGutterRefreshes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, VirtualFile> dirtyFiles = new ConcurrentHashMap<>();

    private final Object scheduleLock = new Object();

    @Getter
    private volatile UserSettingsManager userSettings;

    private ScheduledExecutorService autoScheduler;
    private ScheduledFuture<?> autoCleanTask;
    private ScheduledFuture<?> dirtyRefreshTask;
    private volatile boolean autoUpdateEnabled;

    @Setter
    private volatile Runnable onForceLogoutCallback;

    @Setter
    private volatile Runnable onAuthStateChangedCallback;

    @Setter
    private volatile Runnable onGlobalSettingsChangedCallback;

    public PluginProjectService(Project project) {
        this.project = project;
        this.history = new MethodHistoryRepositoryImpl();
        this.methodHistoryManager = new MethodHistoryManager(history, project);
        this.methodRefreshService = new MethodRefreshService(
                project, methodHistoryManager,
                this::isAutoUpdateEnabled,
                this::requestGutterIconRefresh);
    }

    public void setAutoUpdateEnabled(boolean enabled) {
        synchronized (scheduleLock) {
            setAutoUpdateEnabledLocked(enabled);
        }
    }

    public boolean isAutoUpdateEnabled() {
        synchronized (scheduleLock) {
            return autoUpdateEnabled;
        }
    }

    public void setAutoCleanEnabled(boolean enabled) {
        synchronized (scheduleLock) {
            setAutoCleanEnabledLocked(enabled);
        }
    }

    private void ensureSchedulerLocked() {
        if (autoScheduler == null || autoScheduler.isShutdown()) {
            autoScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "plugin-auto-scheduler");
                t.setDaemon(true);
                return t;
            });
        }
    }

    private void setAutoUpdateEnabledLocked(boolean enabled) {
        autoUpdateEnabled = enabled;
    }

    private void setAutoCleanEnabledLocked(boolean enabled) {
        if (enabled) {
            ensureSchedulerLocked();
            if (autoCleanTask == null || autoCleanTask.isCancelled()) {
                autoCleanTask = autoScheduler.scheduleWithFixedDelay(
                        methodHistoryManager::clearDeletedFileHistories,
                        Constant.AUTO_DELETE_INITIAL_DELAY_MS,
                        Constant.AUTO_DELETE_DELAY_MS,
                        TimeUnit.MILLISECONDS);
            }
        } else if (autoCleanTask != null) {
            autoCleanTask.cancel(false);
            autoCleanTask = null;
        }
    }

    private void registerGutterRefreshListener() {
        PsiManager.getInstance(project).addPsiTreeChangeListener(new PsiTreeChangeAdapter() {
            @Override
            public void childrenChanged(@NotNull PsiTreeChangeEvent event) {
                PsiFile file = event.getFile();
                if (file == null) return;
                VirtualFile vf = file.getVirtualFile();
                if (vf == null || !"java".equalsIgnoreCase(vf.getExtension())) return;
                markFileDirty(vf);
            }
        }, this);
    }

    private void markFileDirty(VirtualFile file) {
        if (file == null || !file.isValid()) {
            return;
        }
        synchronized (scheduleLock) {
            ensureSchedulerLocked();
            dirtyFiles.put(file.getPath(), file);
            if (dirtyRefreshTask != null && !dirtyRefreshTask.isDone()) {
                return;
            }
            dirtyRefreshTask = autoScheduler.schedule(this::consumeDirtyFiles,
                    DIRTY_REFRESH_DEBOUNCE_MS, TimeUnit.MILLISECONDS);
        }
    }

    private void consumeDirtyFiles() {
        List<VirtualFile> files = new ArrayList<>(dirtyFiles.values());
        dirtyFiles.clear();
        synchronized (scheduleLock) {
            dirtyRefreshTask = null;
        }
        boolean allowGeneration = isAutoUpdateEnabled();
        for (VirtualFile file : files) {
            methodRefreshService.refreshFile(file, allowGeneration);
        }
    }

    private void scheduleGutterRefresh(PsiFile file) {
        VirtualFile virtualFile = file.getVirtualFile();
        if (virtualFile == null) {
            return;
        }
        String path = virtualFile.getPath();

        ScheduledFuture<?> prev = pendingGutterRefreshes.get(path);
        if (prev != null) prev.cancel(false);

        synchronized (scheduleLock) {
            ensureSchedulerLocked();
            ScheduledFuture<?> task = autoScheduler.schedule(() ->
                    ApplicationManager.getApplication().invokeLater(() -> {
                        pendingGutterRefreshes.remove(path);
                        if (file.isValid()) {
                            DaemonCodeAnalyzer.getInstance(project).restart(file);
                        }
                    }), Constant.GUTTER_REFRESH_DEBOUNCE_MS, TimeUnit.MILLISECONDS);
            pendingGutterRefreshes.put(path, task);
        }
    }

    public void requestGutterIconRefresh(PsiFile file) {
        if (file == null || !file.isValid()) return;
        scheduleGutterRefresh(file);
    }

    public void requestGutterIconRefresh(VirtualFile file) {
        if (file == null || !file.isValid()) {
            return;
        }
        PsiFile psiFile = com.intellij.openapi.application.ReadAction.compute(() -> PsiManager.getInstance(project).findFile(file));
        if (psiFile != null) {
            requestGutterIconRefresh(psiFile);
        }
    }

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

    public void onUserLogin() {
        String username = AuthManager.getUsername();
        synchronized (scheduleLock) {
            this.userSettings = new UserSettingsManager(username);
            history.clear();
            dirtyFiles.clear();

            applySettingsFromStoreLocked();
            setAutoCleanEnabledLocked(true);
        }
        methodRefreshService.refreshAll(false);
        log.info("用户 {} 登录，已恢复设置", username);
    }

    public void onUserLogout() {
        synchronized (scheduleLock) {
            saveCurrentSettingsLocked();
            setAutoUpdateEnabledLocked(false);
            setAutoCleanEnabledLocked(false);
            history.clear();
            dirtyFiles.clear();
            if (dirtyRefreshTask != null) {
                dirtyRefreshTask.cancel(false);
                dirtyRefreshTask = null;
            }
            userSettings = null;
        }
        log.info("用户已登出，已清理资源");
    }

    public void forceLogout() {
        synchronized (scheduleLock) {
            saveCurrentSettingsLocked();
            setAutoUpdateEnabledLocked(false);
            setAutoCleanEnabledLocked(false);
            history.clear();
            dirtyFiles.clear();
            if (dirtyRefreshTask != null) {
                dirtyRefreshTask.cancel(false);
                dirtyRefreshTask = null;
            }
            userSettings = null;
        }
        AuthManager.clearAuth();
        Runnable cb = onForceLogoutCallback;
        if (cb != null) {
            ApplicationManager.getApplication().invokeLater(cb);
        }
        log.info("强制登出完成");
    }

    public void updateSelectedModel(String model) {
        if (model == null || model.isBlank()) return;
        updateUserSettingsAndBroadcast(settings -> settings.setSelectedModel(model));
    }

    public void updateRagEnabled(boolean enabled) {
        updateUserSettingsAndBroadcast(settings -> settings.setRagEnabled(enabled));
    }

    public void updateRagExampleNum(int count) {
        int normalized = Math.max(1, Math.min(5, count));
        updateUserSettingsAndBroadcast(settings -> settings.setRagExampleNum(normalized));
    }

    public int getRagExampleNum() {
        synchronized (scheduleLock) {
            if (userSettings == null) return 3;
            return userSettings.getRagExampleNum();
        }
    }

    public void updateAutoUpdateEnabled(boolean enabled) {
        synchronized (scheduleLock) {
            if (userSettings == null) return;
            userSettings.setAutoUpdateEnabled(enabled);
            setAutoUpdateEnabledLocked(enabled);
        }
        publishGlobalSettingsChanged();
    }

    public boolean isShowFullApiKeyEnabled() {
        synchronized (scheduleLock) {
            return userSettings != null && userSettings.isShowFullApiKeyEnabled();
        }
    }

    public boolean isApiKeyConfiguredHint() {
        synchronized (scheduleLock) {
            return userSettings != null && userSettings.isApiKeyConfiguredHint();
        }
    }

    public void saveCurrentSettings() {
        synchronized (scheduleLock) {
            saveCurrentSettingsLocked();
        }
    }

    private void saveCurrentSettingsLocked() {
        if (userSettings == null) return;
        userSettings.setSelectedModel(CommentGeneratorClient.getSelectedModel());
        userSettings.setRagEnabled(CommentGeneratorClient.isRagEnabled());
        userSettings.setRagExampleNum(CommentGeneratorClient.getRagExampleNum());
        userSettings.setAutoUpdateEnabled(autoUpdateEnabled);
    }

    private void applySettingsFromStoreLocked() {
        String model = userSettings.getSelectedModel();
        if (model != null && !model.isBlank()) {
            CommentGeneratorClient.setSelectedModel(model);
        }
        CommentGeneratorClient.setRagEnabled(userSettings.isRagEnabled());
        CommentGeneratorClient.setRagExampleNum(userSettings.getRagExampleNum());
        setAutoUpdateEnabledLocked(userSettings.isAutoUpdateEnabled());
    }

    public void syncAuthStateFromGlobal() {
        if (AuthManager.isLoggedIn()) {
            onUserLogin();
        } else {
            onUserLogout();
        }
        invokeUiCallback(onAuthStateChangedCallback);
    }

    public void syncSettingsFromGlobal() {
        if (!AuthManager.isLoggedIn()) return;
        synchronized (scheduleLock) {
            if (userSettings == null) {
                String username = AuthManager.getUsername();
                if (username == null || username.isBlank()) return;
                userSettings = new UserSettingsManager(username);
            }
            applySettingsFromStoreLocked();
        }
        invokeUiCallback(onGlobalSettingsChangedCallback);
    }

    private void invokeUiCallback(Runnable callback) {
        if (callback != null) {
            ApplicationManager.getApplication().invokeLater(callback);
        }
    }

    private void updateUserSettingsAndBroadcast(Consumer<UserSettingsManager> updater) {
        synchronized (scheduleLock) {
            if (userSettings == null) return;
            updater.accept(userSettings);
            applySettingsFromStoreLocked();
        }
        publishGlobalSettingsChanged();
    }

    private void publishGlobalSettingsChanged() {
        PluginApplicationService appService =
                ApplicationManager.getApplication().getService(PluginApplicationService.class);
        if (appService != null) {
            appService.publishGlobalSettingsChanged();
        }
    }

    public void updateApiKeyUiState(boolean configured, boolean showFull) {
        updateUserSettingsAndBroadcast(settings -> {
            settings.setApiKeyConfiguredHint(configured);
            settings.setShowFullApiKeyEnabled(showFull);
        });
    }

    public void refreshAllMethodHistories() {
        methodRefreshService.refreshAll(true);
    }

    public void refreshFileMethodHistories(VirtualFile file) {
        methodRefreshService.refreshFile(file, true);
    }

    public void refreshMethodHistory(PsiMethod method) {
        methodRefreshService.refreshMethod(method, true);
    }

    public void generateComment(PsiMethod method) {
        methodRefreshService.generateComment(method);
    }

    public MethodStatus preCheckChange(PsiMethod method) {
        return methodRefreshService.preCheckChange(method);
    }

    public MethodStatus getCachedMethodStatus(PsiMethod method) {
        return methodRefreshService.getCachedStatus(method);
    }

    @Override
    public void dispose() {
        log.info("项目关闭，释放资源");
        synchronized (scheduleLock) {
            saveCurrentSettingsLocked();
            setAutoCleanEnabledLocked(false);
            dirtyFiles.clear();
            if (dirtyRefreshTask != null) {
                dirtyRefreshTask.cancel(false);
                dirtyRefreshTask = null;
            }
            if (autoScheduler != null) {
                autoScheduler.shutdownNow();
                autoScheduler = null;
            }
        }
        pendingGutterRefreshes.values().forEach(f -> f.cancel(false));
        pendingGutterRefreshes.clear();
    }
}
