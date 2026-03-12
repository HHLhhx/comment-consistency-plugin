package com.nju.comment.service;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.javadoc.PsiDocComment;
import com.intellij.psi.search.FilenameIndex;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.PsiTreeUtil;
import com.nju.comment.client.global.CommentGeneratorClient;
import com.nju.comment.dto.request.CommentReqTag;
import com.nju.comment.history.MethodHistoryManager;
import com.nju.comment.pojo.GenerateOptions;
import com.nju.comment.pojo.MethodRecord;
import com.nju.comment.pojo.MethodStatus;
import com.nju.comment.util.MethodRecordUtil;
import com.nju.comment.util.MethodValidationUtil;
import com.nju.comment.util.TextProcessUtil;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * 方法级刷新 / 生成 / 变更检测的业务逻辑。
 * <p>
 * 与调度基础设施解耦：通过 {@code autoUpdateCheck} 和 {@code gutterRefreshRequester}
 * 两个函数式回调与 {@link PluginProjectService} 交互，避免循环依赖。
 */
@Slf4j
public class MethodRefreshService {

    private static final int MAX_CONCURRENT_REFRESH = 8;

    private final Project project;
    private final MethodHistoryManager historyManager;
    private final BooleanSupplier autoUpdateCheck;
    private final Consumer<PsiFile> gutterRefreshRequester;
    private final Semaphore refreshLimiter = new Semaphore(MAX_CONCURRENT_REFRESH);

    public MethodRefreshService(Project project,
                                MethodHistoryManager historyManager,
                                BooleanSupplier autoUpdateCheck,
                                Consumer<PsiFile> gutterRefreshRequester) {
        this.project = project;
        this.historyManager = historyManager;
        this.autoUpdateCheck = autoUpdateCheck;
        this.gutterRefreshRequester = gutterRefreshRequester;
    }

    // ==================== 刷新入口（含线程编排） ====================

    /**
     * 刷新项目中所有方法历史记录（异步，带智能等待）
     */
    public void refreshAll() {
        DumbService.getInstance(project).runWhenSmart(() ->
                ApplicationManager.getApplication().executeOnPooledThread(this::doRefreshAll));
    }

    /**
     * 刷新单文件中所有方法历史记录
     */
    public void refreshFile(VirtualFile file) {
        DumbService.getInstance(project).runWhenSmart(() ->
                ApplicationManager.getApplication().executeOnPooledThread(() -> doRefreshFile(file)));
    }

    /**
     * 刷新单方法历史记录
     */
    public void refreshMethod(PsiMethod method) {
        DumbService.getInstance(project).runWhenSmart(() ->
                ApplicationManager.getApplication().executeOnPooledThread(() -> doRefreshMethodHistory(method)));
    }

    // ==================== 生成 / 预检 ====================

    /**
     * 为没有注释的方法发起注释生成
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

        MethodRecord record = historyManager.findByKey(methodKey);
        if (record == null || !MethodStatus.NEW_METHOD_WITHOUT_COMMENT.equals(record.getStatus())) {
            log.info("方法不处于可生成注释状态，跳过生成：{}", methodKey);
            return;
        }

        record.setStatus(MethodStatus.GENERATING);
        record.touch();
        historyManager.save(record);
        refreshMethod(method);
    }

    /**
     * 检查方法变更类型（供 Gutter / Action 实时判断）
     */
    public MethodStatus preCheckChange(PsiMethod method) {
        if (method == null) {
            log.warn("方法为空，无法检查变更");
            return null;
        }

        String methodKey = MethodRecordUtil.buildMethodKey(method);
        MethodRecord record = historyManager.findByKey(methodKey);

        String curComment = ReadAction.compute(() -> {
            PsiDocComment pdc = method.getDocComment();
            return pdc != null ? pdc.getText().trim() : "";
        });
        String curMethod = MethodRecordUtil.getMethodTextWithoutComments(method);

        curComment = TextProcessUtil.processComment(curComment);
        curMethod = TextProcessUtil.processMethod(curMethod);

        if (record == null) {
            return curComment.isEmpty()
                    ? MethodStatus.NEW_METHOD_WITHOUT_COMMENT
                    : MethodStatus.NEW_METHOD_WITH_COMMENT;
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

    // ==================== PSI 收集 ====================

    /**
     * 收集项目中所有 Java 方法
     */
    public List<PsiMethod> collectAllMethods() {
        return ReadAction.compute(() -> {
            List<PsiMethod> result = new ArrayList<>();
            Collection<VirtualFile> files = FilenameIndex.getAllFilesByExt(
                    project, "java", GlobalSearchScope.projectScope(project));
            PsiManager psiManager = PsiManager.getInstance(project);
            for (VirtualFile vf : files) {
                PsiFile psiFile = psiManager.findFile(vf);
                if (psiFile == null) continue;
                result.addAll(PsiTreeUtil.collectElementsOfType(psiFile, PsiMethod.class));
            }
            return result;
        });
    }

    // ==================== 内部实现 ====================

    private void doRefreshAll() {
        log.info("刷新项目中所有方法历史记录");
        List<PsiMethod> methods = collectAllMethods();
        log.info("共找到方法数量：{}", methods.size());
        submitRefreshBatch(methods);
    }

    private void doRefreshFile(VirtualFile file) {
        if (file == null || !file.exists() || !"java".equalsIgnoreCase(file.getExtension())) {
            log.warn("文件无效，无法刷新方法历史记录: {}", file);
            return;
        }

        log.info("刷新文件方法历史记录，path: {}", file.getPath());
        List<PsiMethod> methods = ReadAction.compute(() -> {
            PsiFile psiFile = PsiManager.getInstance(project).findFile(file);
            if (psiFile == null) return List.<PsiMethod>of();
            return new ArrayList<>(PsiTreeUtil.collectElementsOfType(psiFile, PsiMethod.class));
        });
        log.info("文件中找到方法数量：{}", methods.size());
        submitRefreshBatch(methods);
    }

    /**
     * 带并发限流的批量提交
     */
    private void submitRefreshBatch(List<PsiMethod> methods) {
        for (PsiMethod method : methods) {
            ApplicationManager.getApplication().executeOnPooledThread(() -> {
                try {
                    refreshLimiter.acquire();
                    doRefreshMethodHistory(method);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                } finally {
                    refreshLimiter.release();
                }
            });
        }
    }

    /**
     * 刷新单方法历史。
     * 同一方法下「重复触发以最初为准、修改后再触发以最近为准」由 CommentGeneratorClient 按内容指纹保证。
     */
    private void doRefreshMethodHistory(PsiMethod method) {
        ReadAction.run(() -> {
            if (!MethodValidationUtil.isValid(method)) return;

            PsiFile psiFile = method.getContainingFile();
            String methodKey = MethodRecordUtil.buildMethodKey(method);
            try {
                historyManager.updateMethodHistoryAsync(method, (context, status) -> {
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

                    CommentGeneratorClient.generateCommentAsync(methodKey, context, options, generatedComment -> {
                        if (generatedComment == null) return;
                        String processed = TextProcessUtil.processComment(generatedComment);

                        ApplicationManager.getApplication().executeOnPooledThread(() -> {
                            MethodRecord record = historyManager.findByKey(methodKey);
                            if (record != null) {
                                record.setStagedComment(processed);
                                if (status.equals(MethodStatus.TO_BE_UPDATE)) {
                                    record.setStatus(MethodStatus.TO_BE_UPDATE);
                                } else if (status.equals(MethodStatus.TO_BE_GENERATE)) {
                                    record.setStatus(MethodStatus.TO_BE_GENERATE);
                                }
                                record.touch();
                                historyManager.save(record);
                                gutterRefreshRequester.accept(psiFile);
                            }
                        });
                    }, project);
                }, autoUpdateCheck.getAsBoolean());
                gutterRefreshRequester.accept(psiFile);
            } catch (Exception ex) {
                log.warn("刷新方法历史记录失败，方法签名：{}", methodKey, ex);
            }
        });
    }
}
