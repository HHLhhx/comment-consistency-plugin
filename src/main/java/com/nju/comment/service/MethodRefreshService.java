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
import com.nju.comment.constant.Constant;
import com.nju.comment.dto.request.CommentReqTag;
import com.nju.comment.history.MethodHistoryManager;
import com.nju.comment.pojo.GenerateOptions;
import com.nju.comment.pojo.MethodRecord;
import com.nju.comment.pojo.MethodRefreshSnapshot;
import com.nju.comment.pojo.MethodStatus;
import com.nju.comment.pojo.MethodValidationResult;
import com.nju.comment.util.MethodRecordUtil;
import com.nju.comment.util.MethodValidationUtil;
import com.nju.comment.util.TextProcessUtil;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.function.Consumer;

/**
 * 文件级方法快照构建器 + 状态更新执行器
 */
@Slf4j
public class MethodRefreshService {

    private final Project project;
    private final MethodHistoryManager historyManager;
    private final Consumer<PsiFile> gutterRefreshRequester;
    private final Semaphore refreshLimiter = new Semaphore(Constant.MAX_CONCURRENT_REFRESH);
    private final Set<String> inFlightFiles = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, Boolean> pendingFileRefreshes = new ConcurrentHashMap<>();

    public MethodRefreshService(Project project,
                                MethodHistoryManager historyManager,
                                Consumer<PsiFile> gutterRefreshRequester) {
        this.project = project;
        this.historyManager = historyManager;
        this.gutterRefreshRequester = gutterRefreshRequester;
    }

    /**
     * 项目初始化时预热所有文件的状态缓存
     */
    public void refreshAll(boolean allowGeneration) {
        DumbService.getInstance(project).runWhenSmart(() ->
                ApplicationManager.getApplication().executeOnPooledThread(() -> doRefreshAll(allowGeneration)));
    }

    /**
     * 当前 dirty 文件的标准处理入口，负责：
     * <ul>
     *     <li>判断是不是有效 Java 文件</li>
     *     <li>通过 inFlightFiles 避免重复处理</li>
     *     <li>在后台线程里执行 doRefreshFile(...)</li>
     * </ul>
     */
    public void refreshFile(VirtualFile file, boolean allowGeneration) {
        if (file == null || !file.exists() || !"java".equalsIgnoreCase(file.getExtension())) {
            return;
        }
        String path = file.getPath();
        if (!inFlightFiles.add(path)) {
            pendingFileRefreshes.merge(path, allowGeneration, Boolean::logicalOr);
            return;
        }

        DumbService.getInstance(project).runWhenSmart(() ->
                ApplicationManager.getApplication().executeOnPooledThread(() -> {
                    boolean acquired = false;
                    try {
                        refreshLimiter.acquire();
                        acquired = true;
                        doRefreshFile(file, allowGeneration);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    } finally {
                        if (acquired) {
                            refreshLimiter.release();
                        }
                        inFlightFiles.remove(path);
                        Boolean rerunAllowGeneration = pendingFileRefreshes.remove(path);
                        if (rerunAllowGeneration != null && file.isValid()) {
                            refreshFile(file, rerunAllowGeneration);
                        }
                    }
                }));
    }

    public void refreshMethod(PsiMethod method, boolean allowGeneration) {
        DumbService.getInstance(project).runWhenSmart(() ->
                ApplicationManager.getApplication().executeOnPooledThread(() -> doRefreshSingleMethod(method, allowGeneration)));
    }

    public void generateComment(PsiMethod method) {
        MethodRefreshSnapshot snapshot = buildMethodSnapshot(method);
        if (snapshot == null || snapshot.getValidationResult() == null || !snapshot.getValidationResult().isValid()) {
            return;
        }

        doProcessSnapshot(snapshot, getVirtualFile(method), false);
        MethodRecord record = historyManager.findByKey(snapshot.getMethodKey());
        if (record == null || !MethodStatus.NEW_METHOD_WITHOUT_COMMENT.equals(record.getStatus())) {
            return;
        }

        record.setStatus(MethodStatus.GENERATING);
        record.touch();
        historyManager.save(record);
        doProcessSnapshot(snapshot, getVirtualFile(method), true);
    }

    public MethodStatus preCheckChange(PsiMethod method) {
        if (!MethodValidationUtil.isQuicklyEligible(method)) {
            return null;
        }

        String methodKey = MethodRecordUtil.buildMethodKey(method);
        MethodRecord record = historyManager.findByKey(methodKey);
        long sourceStamp = MethodRecordUtil.getSourceStamp(method);
        if (record != null && record.hasFreshValidation(sourceStamp)) {
            MethodValidationResult validationResult = record.getValidationResult();
            if (validationResult != null && !validationResult.isValid()) {
                return null;
            }
        }

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
        }

        if (MethodStatus.NEW_METHOD_WITHOUT_COMMENT.equals(record.getStatus())
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
     * 供 gutter 和部分 UI 读取“已验证且未过期的缓存状态”
     */
    public MethodStatus getCachedStatus(PsiMethod method) {
        if (!MethodValidationUtil.isQuicklyEligible(method)) {
            return null;
        }
        String methodKey = MethodRecordUtil.buildMethodKey(method);
        MethodRecord record = historyManager.findByKey(methodKey);
        if (record == null) {
            return null;
        }
        long sourceStamp = MethodRecordUtil.getSourceStamp(method);
        if (!record.hasFreshValidation(sourceStamp)) {
            return null;
        }
        MethodValidationResult validationResult = record.getValidationResult();
        if (validationResult == null || !validationResult.isValid()) {
            return null;
        }
        return record.getStatus();
    }

    private void doRefreshAll(boolean allowGeneration) {
        List<VirtualFile> files = collectAllJavaFiles();
        for (VirtualFile file : files) {
            refreshFile(file, allowGeneration);
        }
    }

    private List<VirtualFile> collectAllJavaFiles() {
        return ReadAction.compute(() ->
                new ArrayList<>(FilenameIndex.getAllFilesByExt(
                        project, "java", GlobalSearchScope.projectScope(project))));
    }

    private void doRefreshFile(VirtualFile file, boolean allowGeneration) {
        FileRefreshPayload payload = buildFilePayload(file);
        if (payload == null) {
            return;
        }

        boolean changed = false;
        for (MethodRefreshSnapshot snapshot : payload.snapshots()) {
            changed |= doProcessSnapshot(snapshot, file, allowGeneration);
        }
        changed |= historyManager.clearMissingMethodHistories(payload.filePath(), payload.currentMethodKeys());

        if (changed) {
            requestGutterRefresh(file);
        }
    }

    private void doRefreshSingleMethod(PsiMethod method, boolean allowGeneration) {
        MethodRefreshSnapshot snapshot = buildMethodSnapshot(method);
        if (snapshot == null) {
            return;
        }
        boolean changed = doProcessSnapshot(snapshot, getVirtualFile(method), allowGeneration);
        if (changed) {
            requestGutterRefresh(getVirtualFile(method));
        }
    }

    private boolean doProcessSnapshot(MethodRefreshSnapshot snapshot, VirtualFile file, boolean allowGeneration) {
        return historyManager.updateMethodHistoryAsync(snapshot, (context, status) -> {
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
                    .ragExampleNum(CommentGeneratorClient.getRagExampleNum())
                    .build();

            String methodKey = snapshot.getMethodKey();
            CommentGeneratorClient.generateCommentAsync(methodKey, context, options, generatedComment -> {
                if (generatedComment == null) return;
                String processed = TextProcessUtil.processComment(generatedComment);

                ApplicationManager.getApplication().executeOnPooledThread(() -> {
                    MethodRecord record = historyManager.findByKey(methodKey);
                    if (record == null) {
                        return;
                    }

                    MethodStatus previousStatus = record.getStatus();
                    String previousComment = record.getStagedComment();

                    record.setStagedComment(processed);
                    if (status.equals(MethodStatus.TO_BE_UPDATE)) {
                        record.setStatus(MethodStatus.TO_BE_UPDATE);
                    } else if (status.equals(MethodStatus.TO_BE_GENERATE)) {
                        record.setStatus(MethodStatus.TO_BE_GENERATE);
                    }
                    record.touch();
                    historyManager.save(record);

                    if (previousStatus != record.getStatus()
                            || !TextProcessUtil.safeTrimNullable(previousComment)
                            .equals(TextProcessUtil.safeTrimNullable(processed))) {
                        requestGutterRefresh(file);
                    }
                });
            }, project);
        }, allowGeneration);
    }

    /**
     * 提取当前版本下的“方法全景”
     */
    private FileRefreshPayload buildFilePayload(VirtualFile file) {
        return ReadAction.compute(() -> {
            if (file == null || !file.isValid()) {
                return null;
            }
            PsiFile psiFile = PsiManager.getInstance(project).findFile(file);
            if (psiFile == null) {
                return null;
            }

            Collection<PsiMethod> methods = PsiTreeUtil.collectElementsOfType(psiFile, PsiMethod.class);
            List<MethodRefreshSnapshot> snapshots = new ArrayList<>();
            Set<String> currentKeys = new HashSet<>();
            for (PsiMethod method : methods) {
                MethodRefreshSnapshot snapshot = buildMethodSnapshotUnsafely(method);
                if (snapshot == null) {
                    continue;
                }
                snapshots.add(snapshot);
                currentKeys.add(snapshot.getMethodKey());
            }
            return new FileRefreshPayload(file.getPath(), snapshots, currentKeys);
        });
    }

    private MethodRefreshSnapshot buildMethodSnapshot(PsiMethod method) {
        return ReadAction.compute(() -> buildMethodSnapshotUnsafely(method));
    }

    /**
     * 对方法进行一次性抽取，生成 snapshot，同时完成：
     * <ul>
     *     <li>把方法当前文本和注释取出来</li>
     *     <li>计算强校验结果</li>
     * </ul>
     */
    private MethodRefreshSnapshot buildMethodSnapshotUnsafely(PsiMethod method) {
        if (method == null || !method.isValid()) {
            return null;
        }
        String filePath = getFilePathUnsafely(method);
        String qualifiedName = getQualifiedNameUnsafely(method);
        String signature = getSignatureUnsafely(method);
        if (filePath == null || qualifiedName.isBlank() || signature == null || signature.isBlank()) {
            return null;
        }

        PsiDocComment docComment = method.getDocComment();
        String currentComment = docComment != null ? docComment.getText().trim() : "";
        String currentMethod = MethodRecordUtil.getMethodTextWithoutComments(method);
        MethodValidationResult validationResult = MethodValidationUtil.validateCompilable(method);

        return MethodRefreshSnapshot.builder()
                .filePath(filePath)
                .qualifiedName(qualifiedName)
                .signature(signature)
                .currentMethod(currentMethod)
                .currentComment(currentComment)
                .validationResult(validationResult)
                .build();
    }

    private void requestGutterRefresh(VirtualFile file) {
        if (file == null || !file.isValid()) {
            return;
        }
        PsiFile psiFile = ReadAction.compute(() -> PsiManager.getInstance(project).findFile(file));
        if (psiFile != null && psiFile.isValid()) {
            gutterRefreshRequester.accept(psiFile);
        }
    }

    private VirtualFile getVirtualFile(PsiMethod method) {
        return ReadAction.compute(() -> {
            if (method == null || !method.isValid()) {
                return null;
            }
            PsiFile psiFile = method.getContainingFile();
            return psiFile != null ? psiFile.getVirtualFile() : null;
        });
    }

    private String getFilePathUnsafely(PsiMethod method) {
        PsiFile psiFile = method.getContainingFile();
        if (psiFile == null) {
            return null;
        }
        VirtualFile vf = psiFile.getVirtualFile();
        return vf != null ? vf.getPath() : null;
    }

    private String getQualifiedNameUnsafely(PsiMethod method) {
        if (method == null) {
            return "";
        }
        if (method.getContainingClass() == null) {
            return "";
        }
        String qualifiedName = method.getContainingClass().getQualifiedName();
        return qualifiedName != null ? qualifiedName : "";
    }

    private String getSignatureUnsafely(PsiMethod method) {
        return MethodRecordUtil.getMethodSignature(method);
    }

    private String getMethodTextWithoutCommentsUnsafely(PsiMethod method) {
        PsiFile containingFile = method.getContainingFile();
        if (containingFile == null) {
            return "";
        }
        PsiDocumentManager.getInstance(project).commitAllDocuments();
        var firstChild = method.getFirstChild();
        while (firstChild instanceof PsiComment
                || firstChild instanceof PsiWhiteSpace) {
            firstChild = firstChild.getNextSibling();
        }
        if (firstChild == null) {
            return "";
        }
        int methodStartOffset = firstChild.getTextRange().getStartOffset();
        int endOffset = method.getTextRange().getEndOffset();
        return containingFile.getText().substring(methodStartOffset, endOffset).trim();
    }

    private record FileRefreshPayload(String filePath,
                                      List<MethodRefreshSnapshot> snapshots,
                                      Set<String> currentMethodKeys) {
    }
}
