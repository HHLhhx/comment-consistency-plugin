package com.nju.comment.service;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.search.FilenameIndex;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.PsiTreeUtil;
import com.nju.comment.client.global.CommentGeneratorClient;
import com.nju.comment.constant.Constant;
import com.nju.comment.dto.GenerateOptions;
import com.nju.comment.dto.MethodStatus;
import com.nju.comment.history.MethodHistoryManager;
import com.nju.comment.history.MethodHistoryRepositoryImpl;
import com.nju.comment.util.TextProcessUtil;
import com.nju.comment.util.MethodRecordUtil;
import com.nju.comment.dto.MethodRecord;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.*;

@Slf4j
@Service(Service.Level.PROJECT)
public final class PluginProjectService implements Disposable {

    private static final String DEFAULT_BASE_URL = Constant.CLIENT_DEFAULT_BASE_URL;

    private final Project project;
    private final MethodHistoryManager methodHistoryManager;

    @Getter
    private final CompletableFuture<Void> initializationFuture = new CompletableFuture<>();

    public PluginProjectService(Project project) {
        this.project = project;
        this.methodHistoryManager = new MethodHistoryManager(MethodHistoryRepositoryImpl.getInstance());
    }

    /**
     * 项目启动时初始化
     */
    public void initialize() {
        log.info("项目启动初始化");
        CommentGeneratorClient.init(DEFAULT_BASE_URL);
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            CommentGeneratorClient.getAvailableModels();
            initializationFuture.complete(null);
        });
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
            if (!isValid(method)) return;

            String methodKey = MethodRecordUtil.buildMethodKey(method);
            try {
                GenerateOptions options = new GenerateOptions(CommentGeneratorClient.getSelectedModel());
                methodHistoryManager.updateMethodHistoryAsync(method, (context, status) -> {
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
                            }
                        });
                    });
                });
            } catch (Exception ex) {
                log.warn("刷新方法历史记录失败，方法签名：{}", methodKey, ex);
            }
        });
    }

    /**
     * 生成方法注释
     * @param method 目标方法
     */
    public void generateComment(PsiMethod method) {
        if (method == null) {
            log.warn("方法为空，无法生成注释");
            return;
        }

        String methodKey = MethodRecordUtil.buildMethodKey(method);
        MethodRecord record = methodHistoryManager.findByKey(methodKey);
        if (record == null) {
            // 方法记录不存在，刷新一次，此时为 NEW_METHOD_WITHOUT_COMMENT 或 NEW_METHOD_WITH_COMMENT 状态
            doRefreshMethodHistory(method);
        }

        record = methodHistoryManager.findByKey(methodKey);
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
     * 获取方法状态
     * @param method 目标方法
     * @return 方法状态
     */
    public MethodStatus getMethodStatus(PsiMethod method) {
        if (method == null) {
            log.warn("方法为空，无法获取状态");
            return null;
        }

        String methodKey = MethodRecordUtil.buildMethodKey(method);
        MethodRecord record = methodHistoryManager.findByKey(methodKey);
        if (record == null) {
            // 方法记录不存在，刷新一次
            doRefreshMethodHistory(method);
        }

        record = methodHistoryManager.findByKey(methodKey);
        if (record == null) {
            log.warn("方法记录不存在，无法获取状态：{}", methodKey);
            return null;
        }

        return record.getStatus();
    }

    /**
     * 方法有效性校验
     *
     * @param method 目标方法
     * @return 是否有效
     */
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

    /**
     * 项目关闭时释放资源
     */
    @Override
    public void dispose() {
        log.info("项目关闭，释放资源");
        CommentGeneratorClient.shutdown();
    }
}
