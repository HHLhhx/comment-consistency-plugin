package com.nju.comment.history;

import com.intellij.openapi.application.ReadAction;
import com.intellij.psi.PsiComment;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiWhiteSpace;
import com.intellij.psi.javadoc.PsiDocComment;
import com.nju.comment.dto.*;
import com.nju.comment.util.TextProcessUtil;
import com.nju.comment.util.MethodRecordUtil;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.function.BiConsumer;

@Slf4j
public record MethodHistoryManager(MethodHistoryRepository repository) {

    /**
     * 异步更新方法历史记录
     *
     * @param method                当前方法
     * @param commentGeneratorAsync 用于生成注释的异步回调函数，接受MethodContext和MethodStatus两个参数
     */
    public void updateMethodHistoryAsync(PsiMethod method, BiConsumer<MethodContext, MethodStatus> commentGeneratorAsync) {
        // 在ReadAction中获取方法相关信息，避免阻塞UI线程
        Object[] info = ReadAction.compute(() -> {
            String path = MethodRecordUtil.getFilePath(method);
            String qualifiedName = MethodRecordUtil.getQualifiedNameContainClass(method);
            String signature = MethodRecordUtil.getMethodSignature(method);
            PsiDocComment pdc = method.getDocComment();
            String comment = pdc != null ? pdc.getText().trim() : "";

            String mtd = getMethodTextWithoutComments(method);

            return new Object[]{path, qualifiedName, signature, comment, mtd};
        });

        String path = (String) info[0];
        if (path == null) return;

        String qualifiedName = (String) info[1];
        if (qualifiedName == null) return;

        String signature = (String) info[2];
        if (signature == null || signature.isBlank()) return;

        String curComment = (String) info[3];
        String curMethod = (String) info[4];

        curComment = TextProcessUtil.processComment(curComment);
        curMethod = TextProcessUtil.processMethod(curMethod);

        // 查找历史记录
        String key = MethodRecordUtil.buildMethodKey(qualifiedName, signature);
        MethodRecord record = repository.findByKey(key);

        if (record == null) {
            // 历史记录不存在（新方法）
            if (!curComment.isEmpty()) {
                // 有currentComment，用currentComment和currentMethod更新历史记录
                log.info("status: new method with comment");

                MethodRecord r = new MethodRecord(qualifiedName, signature, curMethod, curComment);
                r.createMethodPointer(method);
                r.setStagedMethod(curMethod);
                r.setFilePath(path);
                r.setTag(0);
                repository.save(r);
            } else {
                // 无currentComment，生成currentComment，更新历史记录
                log.info("status: new method without comment");

                MethodRecord r = new MethodRecord(qualifiedName, signature, curMethod, "");
                r.createMethodPointer(method);
                r.setStagedMethod(curMethod);
                r.setFilePath(path);
                r.setTag(0);
                repository.save(r);

                // 异步调用，不阻塞
                MethodContext context = new MethodContext("", "", curMethod);
                commentGeneratorAsync.accept(context, MethodStatus.NEW_METHOD_WITHOUT_COMMENT);
            }
        } else {
            // 历史记录存在
            String oldMethod = TextProcessUtil.processMethod(record.getOldMethod());
            String oldComment = TextProcessUtil.processComment(record.getOldComment());
            String stagedMethod = TextProcessUtil.processMethod(record.getStagedMethod());

            if (curMethod.equals(oldMethod) || curMethod.equals(stagedMethod)) {
                // currentMethod与oldMethod相同
                if (oldComment.equals(curComment)) {
                    // currentComment与oldComment相同，不处理
                    log.info("status: unchanged");
                    record.setStagedMethod(curMethod);
                    repository.save(record);
                } else {
                    // currentComment与oldComment不同，用currentComment更新历史记录
                    log.info("status: comment changed");
                    record.setOldComment(curComment);
                    record.setStagedMethod(curMethod);
                    record.clearStagedComment();
                    record.setTag(0);
                    repository.save(record);
                }
            } else {
                // currentMethod与oldMethod不同
                if (oldComment.isEmpty() && curComment.isEmpty()) {
                    // oldComment为空，更新历史记录
                    log.info("status: method changed and both comments empty");

                    // 异步调用，不阻塞
                    MethodContext context = new MethodContext("", "", curMethod);
                    commentGeneratorAsync.accept(context, MethodStatus.METHOD_CHANGED_BOTH_EMPTY_COMMENT);
                } else if (oldComment.equals(curComment)) {
                    // currentComment与oldComment相同，生成currentComment，更新历史记录
                    log.info("status: method changed");

                    // 异步调用，不阻塞
                    MethodContext context = new MethodContext(record.getOldMethod(), record.getOldComment(), curMethod);
                    commentGeneratorAsync.accept(context, MethodStatus.METHOD_CHANGED);
                } else {
                    // currentComment与oldComment不同，用currentComment和currentMethod更新历史记录
                    log.info("status: both changed");
                    record.setOldMethod(curMethod);
                    record.setOldComment(curComment);
                    record.setStagedMethod(curMethod);
                    record.clearStagedComment();
                    record.setTag(0);
                    record.touch();
                    repository.save(record);
                }
            }
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
                log.info("删除已不存在的方法历史记录，signature: {}", record.getKey());
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
    }

    /**
     * 打印所有方法历史记录（用于调试）
     */
    public void printAllMethodRecords() {
        for (MethodRecord record : repository.findAll()) {
            log.info("""
                            
                            MethodRecord - key: {}
                            oldMethod:\s
                            {}
                            oldComment:\s
                            {}
                            stagedMethod:\s
                            {}
                            stagedComment:\s
                            {}
                            tag: {}
                            updatedAt: {}
                            filePath: {}""",
                    record.getKey(),
                    TextProcessUtil.processMethod(record.getOldMethod()),
                    TextProcessUtil.processComment(record.getOldComment()),
                    TextProcessUtil.processMethod(record.getStagedMethod()),
                    TextProcessUtil.processComment(record.getStagedComment()),
                    record.getTag(), record.getUpdatedAt(),
                    record.getFilePath());
        }
    }
}
