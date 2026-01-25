package com.nju.comment.service;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.search.FilenameIndex;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.PsiTreeUtil;
import com.nju.comment.client.global.CommentGeneratorClient;
import com.nju.comment.dto.GenerateOptions;
import com.nju.comment.history.MethodHistoryManager;
import com.nju.comment.history.MethodHistoryRepositoryImpl;
import com.nju.comment.util.TextProcessUtil;
import com.nju.comment.util.MethodRecordUtil;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.*;

@Slf4j
@Service(Service.Level.PROJECT)
public final class PluginProjectService implements Disposable {

    private static final String DEFAULT_BASE_URL = "http://localhost:8080/api";

    private final Project project;
    private final MethodHistoryManager methodHistoryManager;

    @Getter
    private final CompletableFuture<Void> initializationFuture = new CompletableFuture<>();

    public PluginProjectService(Project project) {
        this.project = project;
        this.methodHistoryManager = new MethodHistoryManager(MethodHistoryRepositoryImpl.getInstance());
    }

    public void initialize() {
        log.info("项目启动初始化");
        CommentGeneratorClient.init(DEFAULT_BASE_URL);
        ApplicationManager.getApplication().executeOnPooledThread(CommentGeneratorClient::getAvailableModels);
        DumbService.getInstance(project).runWhenSmart(this::refreshAllMethodHistories);

        initializationFuture.complete(null);
    }

    public void refreshAllMethodHistories() {
        ApplicationManager.getApplication().executeOnPooledThread(this::doRefreshAllMethodHistories);
    }

    private void doRefreshAllMethodHistories() {
        log.info("刷新项目中所有方法历史记录");
        List<PsiMethod> methods = collectAllMethods(project);
        log.info("共找到方法数量：{}", methods.size());

        List<Future<?>> futures = new ArrayList<>();
        for (PsiMethod method : methods) {
            Future<?> future = ApplicationManager.getApplication().executeOnPooledThread(() -> doRefreshMethodHistory(method));
            futures.add(future);
        }

        try {
            for (Future<?> future : futures) {
                try {
                    future.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("等待方法历史记录刷新任务完成时被中断", e);
                } catch (ExecutionException e) {
                    log.warn("方法历史记录刷新任务执行时发生异常", e.getCause());
                }
            }
            log.info("所有方法历史记录刷新任务已完成");
        } catch (Exception ex) {
            log.warn("等待方法历史记录刷新任务完成时发生异常", ex);
        }
    }

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

    public void refreshFileMethodHistories(VirtualFile file) {
        ApplicationManager.getApplication().executeOnPooledThread(() -> doRefreshFileMethodHistories(file));
    }

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

        List<Future<?>> futures = new ArrayList<>();
        for (PsiMethod method : methods) {
            Future<?> future = ApplicationManager.getApplication().executeOnPooledThread(() -> doRefreshMethodHistory(method));
            futures.add(future);
        }

        try {
            for (Future<?> future : futures) {
                try {
                    future.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("等待文件方法历史记录刷新任务完成时被中断", e);
                } catch (ExecutionException e) {
                    log.warn("文件方法历史记录刷新任务执行时发生异常", e.getCause());
                }
            }
            log.info("文件所有方法历史记录刷新任务已完成，path: {}", file.getPath());
        } catch (Exception ex) {
            log.warn("等待文件方法历史记录刷新任务完成时发生异常，path: {}", file.getPath(), ex);
        }
    }

    public void refreshMethodHistory(PsiMethod method) {
        ApplicationManager.getApplication().executeOnPooledThread(() -> doRefreshMethodHistory(method));
    }

    private void doRefreshMethodHistory(PsiMethod method) {
        ReadAction.run(() -> {
            if (!isValid(method)) return;

            try {
                GenerateOptions options = new GenerateOptions(CommentGeneratorClient.getSelectedModel());
                methodHistoryManager.updateMethodHistory(method,
                        context -> {
                            if (context.getOldMethod() == null || context.getOldMethod().isBlank()) {
                                return "TODO";
                            }
                            String generated = CommentGeneratorClient.generateComment(context, options);
                            return TextProcessUtil.processComment(generated);
                        });
            } catch (Exception ex) {
                log.warn("刷新方法历史记录失败，方法签名：{}", MethodRecordUtil.buildMethodKey(method), ex);
            }
        });
    }

    private static boolean isValid(PsiMethod method) {
        String key = MethodRecordUtil.buildMethodKey(method);

        if (method == null || !method.isValid()) {
            log.warn("方法无效，无法刷新方法历史记录: {}", method);
            return false;
        }

        if (method.getBody() == null) {
            log.warn("方法无方法体，跳过刷新：{}", key);
            return false;
        }

        if (!PsiTreeUtil.findChildrenOfType(method, PsiErrorElement.class).isEmpty()) {
            log.warn("方法存在语法错误，跳过刷新：{}", key);
            return false;
        }

        Collection<PsiMethodCallExpression> calls = PsiTreeUtil.findChildrenOfType(method, PsiMethodCallExpression.class);
        for (PsiMethodCallExpression call : calls) {
            if (call == null) continue;
            if (call.resolveMethod() == null) {
                log.warn("方法存在未解析的方法调用，跳过刷新：{}", key);
                return false;
            }
        }

        Collection<PsiJavaCodeReferenceElement> typeRefs = PsiTreeUtil.findChildrenOfType(method, PsiJavaCodeReferenceElement.class);
        for (PsiJavaCodeReferenceElement typeRef : typeRefs) {
            if (typeRef == null) continue;
            if (typeRef.resolve() == null) {
                log.warn("方法存在未解析的类型引用，跳过刷新：{}", key);
                return false;
            }
        }

        Collection<PsiReferenceExpression> refs = PsiTreeUtil.findChildrenOfType(method, PsiReferenceExpression.class);
        for (PsiReferenceExpression ref : refs) {
            if (ref == null) continue;
            PsiElement resolved = ref.resolve();
            if (resolved == null) {
                log.warn("方法包含未解析的引用，跳过刷新：{} -> {}", MethodRecordUtil.buildMethodKey(method), ref.getText());
                return false;
            }
        }

        PsiType returnType = method.getReturnType();
        if (returnType != null && !returnType.equalsToText("void")) {
            Collection<PsiReturnStatement> returns = PsiTreeUtil.findChildrenOfType(method, PsiReturnStatement.class);
            if (returns.isEmpty()) {
                Collection<PsiThrowStatement> throwsStmts = PsiTreeUtil.findChildrenOfType(method, PsiThrowStatement.class);
                if (throwsStmts.isEmpty()) {
                    log.warn("非void方法缺少return语句，且无throw语句，跳过刷新：{}", key);
                    return false;
                }
            } else {
                for (PsiReturnStatement rs : returns) {
                    if (rs == null) continue;
                    PsiExpression rv = rs.getReturnValue();
                    if (rv == null) {
                        log.warn("非void方法存在空return语句，跳过刷新：{}", key);
                        return false;
                    }
                }
            }
        }

        //TODO: 更多校验规则

        return true;
    }

    @Override
    public void dispose() {
        log.info("项目关闭，释放资源");
        CommentGeneratorClient.shutdown();
    }
}
