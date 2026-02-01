package com.nju.comment.history;

import com.intellij.openapi.application.ReadAction;
import com.intellij.psi.PsiComment;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiWhiteSpace;
import com.intellij.psi.javadoc.PsiDocComment;
import com.nju.comment.client.global.CommentGeneratorClient;
import com.nju.comment.dto.*;
import com.nju.comment.history.state.MethodStateContext;
import com.nju.comment.history.state.MethodStateMachine;
import com.nju.comment.history.state.MethodStateResult;
import com.nju.comment.util.MethodRecordUtil;
import com.nju.comment.util.TextProcessUtil;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.function.BiConsumer;

@Slf4j
public record MethodHistoryManager(MethodHistoryRepository repository) {

    private static final MethodStateMachine STATE_MACHINE = new MethodStateMachine();

    /**
     * 异步更新方法历史记录
     *
     * @param method                当前方法
     * @param commentGeneratorAsync 用于生成注释的异步回调函数，接受MethodContext和MethodStatus两个参数
     */
    public void updateMethodHistoryAsync(PsiMethod method, BiConsumer<MethodContext, MethodStatus> commentGeneratorAsync) {
        // 提取方法关键信息
        String path = MethodRecordUtil.getFilePath(method);
        if (path == null) return;

        String qualifiedName = MethodRecordUtil.getQualifiedNameContainClass(method);
        if (qualifiedName == null) return;

        String signature = MethodRecordUtil.getMethodSignature(method);
        if (signature == null || signature.isBlank()) return;

        String curComment = ReadAction.compute(() -> {
            PsiDocComment pdc = method.getDocComment();
            return pdc != null ? pdc.getText().trim() : "";
        });
        String curMethod = getMethodTextWithoutComments(method);

        // 预处理文本
        curComment = TextProcessUtil.processComment(curComment);
        curMethod = TextProcessUtil.processMethod(curMethod);

        // 查找历史记录并评估状态
        String key = MethodRecordUtil.buildMethodKey(qualifiedName, signature);
        MethodRecord record = repository.findByKey(key);

        // 构建状态机上下文并评估
        MethodStateContext ctx = new MethodStateContext(method, record, curMethod, curComment, path, qualifiedName, signature);
        MethodStateResult result = STATE_MACHINE.evaluate(ctx);

        // 保存更新后的记录（如有更改）
        MethodRecord updatedRecord = result.record();
        if (updatedRecord != null && (result.recordChanged() || record == null)) {
            repository.save(updatedRecord);
        }

        log.info("methodKey: {}, status: {}", key, result.state().toString());

        if (result.requiresGeneration()) {
            // 调用异步注释生成回调
            result.generationContext().ifPresent(methodContext ->
                    result.generationStatus().ifPresent(status ->
                            commentGeneratorAsync.accept(methodContext, status)));
        } else if (result.requiresCancel()) {
            // 取消在途注释生成请求
            CommentGeneratorClient.cancelForMethod(key);
        }
    }

    /**
     * 清理已删除方法的历史记录
     *
     * @param methods 当前存在的方法列表
     */
    public void clearDeletedMethodHistories(List<PsiMethod> methods) {
        List<String> existingMethods = ReadAction.compute(() ->
                methods.stream()
                        .map(MethodRecordUtil::buildMethodKey)
                        .filter(s -> !s.isBlank())
                        .toList()
        );

        List<MethodRecord> allRecords = repository.findAll();
        for (MethodRecord record : allRecords) {
            if (!existingMethods.contains(record.getKey())) {
                log.info("删除已不存在的方法记录，key: {}", record.getKey());
                deleteByKey(record.getKey());
            }
        }
    }

    /**
     * 根据key查找方法历史记录
     *
     * @param key 方法唯一标识符
     * @return 方法历史记录
     */
    public MethodRecord findByKey(String key) {
        return repository.findByKey(key);
    }

    /**
     * 保存方法历史记录
     *
     * @param record 方法历史记录
     */
    public void save(MethodRecord record) {
        repository.save(record);
    }

    /**
     * 根据key删除方法历史记录
     *
     * @param key 方法唯一标识符
     */
    public void deleteByKey(String key) {
        repository.deleteByKey(key);
    }

    /**
     * 查找所有方法历史记录
     *
     * @return 方法历史记录列表
     */
    public List<MethodRecord> findAll() {
        return repository.findAll();
    }

    /**
     * 获取方法文本内容，去除注释部分
     *
     * @param method 方法
     * @return 方法文本内容
     */
    private static @NotNull String getMethodTextWithoutComments(PsiMethod method) {
        return ReadAction.compute(() -> {
            PsiElement firstChild = method.getFirstChild();
            while (firstChild instanceof PsiComment ||
                    firstChild instanceof PsiWhiteSpace) {
                firstChild = firstChild.getNextSibling();
            }

            String mtd = "";
            if (firstChild != null) {
                int methodStartOffset = firstChild.getTextRange().getStartOffset();
                int endOffset = method.getTextRange().getEndOffset();
                mtd = method.getContainingFile().getText().substring(methodStartOffset, endOffset).trim();
            }
            return mtd;
        });
    }

    /**
     * 打印所有方法历史记录（用于调试）
     */
    public void printAllMethodRecords() {
        for (MethodRecord record : repository.findAll()) {
            System.out.println(record);
        }
    }
}
