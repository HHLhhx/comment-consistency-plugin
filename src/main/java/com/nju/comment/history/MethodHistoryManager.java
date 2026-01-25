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
import java.util.function.Function;

@Slf4j
public record MethodHistoryManager(MethodHistoryRepository repository) {

    public void updateMethodHistory(PsiMethod method, Function<MethodContext, String> commentGenerator) {
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

        String key = MethodRecordUtil.buildMethodKey(qualifiedName, signature);
        MethodRecord record = repository.findByKey(key);

        if (record == null) {
            // 历史记录不存在（新方法）
            if (!curComment.isEmpty()) {
                // 有currentComment，用currentComment和currentMethod更新历史记录
                log.info("status: new method with comment");

                MethodRecord r = new MethodRecord(qualifiedName, signature, curMethod, curComment);
                r.createMethodPointer(method);
                r.setFilePath(path);
                r.setTag(0);
                repository.save(r);
            } else {
                // 无currentComment，生成currentComment，更新历史记录
                log.info("status: new method without comment");
                MethodContext context = new MethodContext("", "", curMethod);
                String newComment = TextProcessUtil.processComment(commentGenerator.apply(context));

                MethodRecord r = new MethodRecord(qualifiedName, signature, curMethod, null);
                r.createMethodPointer(method);
                r.setFilePath(path);
                r.setStagedComment(newComment);
                r.setTag(1);
                repository.save(r);
            }
        } else {
            // 历史记录存在
            String oldMethod = TextProcessUtil.processMethod(record.getOldMethod());
            String oldComment = TextProcessUtil.processComment(record.getOldComment());

            if (oldMethod.equals(curMethod)) {
                // currentMethod与oldMethod相同
                if (oldComment.equals(curComment)) {
                    // currentComment与oldComment相同，不处理
                    log.info("status: unchanged");
                    repository.save(record);
                } else {
                    // currentComment与oldComment不同，用currentComment更新历史记录
                    log.info("status: comment changed");
                    record.setOldComment(curComment);
                    record.clearStaged();
                    record.setTag(0);
                    repository.save(record);
                }
            } else {
                // currentMethod与oldMethod不同
                if (oldComment.isEmpty() && curComment.isEmpty()) {
                    // oldComment为空，更新历史记录
                    log.info("status: method changed and both comments empty");
                    MethodContext context = new MethodContext("", "", curMethod);
                    String newComment = TextProcessUtil.processComment(commentGenerator.apply(context));

                    record.setOldMethod(curMethod);
                    record.setStagedComment(newComment);
                    record.setTag(1);
                } else if (oldComment.equals(curComment)) {
                    // currentComment与oldComment相同，生成currentComment，更新历史记录
                    log.info("status: method changed");
                    MethodContext context = new MethodContext(record.getOldMethod(), record.getOldComment(), curMethod);
                    String newComment = TextProcessUtil.processComment(commentGenerator.apply(context));

                    record.setOldMethod(curMethod);
                    record.setStagedComment(newComment);
                    record.setTag(1);
                } else {
                    // currentComment与oldComment不同，用currentComment和currentMethod更新历史记录
                    log.info("status: both changed");
                    record.setOldMethod(curMethod);
                    record.setOldComment(curComment);
                    record.clearStaged();
                    record.setTag(0);
                }
                record.touch();
                repository.save(record);
            }
        }
    }

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
                repository.deleteByKey(record.getKey());
            }
        }
    }

    public void printAllMethodRecords() {
        for (MethodRecord record : repository.findAll()) {
            log.info("""
                            
                            MethodRecord - key: {}
                            oldMethod:\s
                            {}
                            oldComment:\s
                            {}
                            stagedComment:\s
                            {}
                            updatedAt: {}
                            tag: {}
                            filePath: {}""",
                    record.getKey(),
                    TextProcessUtil.processMethod(record.getOldMethod()),
                    TextProcessUtil.processComment(record.getOldComment()),
                    TextProcessUtil.processComment(record.getStagedComment()),
                    record.getUpdatedAt(), record.getTag(),
                    record.getFilePath());
        }
    }
}
