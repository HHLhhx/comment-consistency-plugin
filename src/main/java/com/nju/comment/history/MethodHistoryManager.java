package com.nju.comment.history;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.nju.comment.client.global.CommentGeneratorClient;
import com.nju.comment.history.state.MethodStateContext;
import com.nju.comment.history.state.MethodStateMachine;
import com.nju.comment.history.state.MethodStateResult;
import com.nju.comment.pojo.MethodContext;
import com.nju.comment.pojo.MethodRecord;
import com.nju.comment.pojo.MethodRefreshSnapshot;
import com.nju.comment.pojo.MethodStatus;
import com.nju.comment.pojo.MethodValidationResult;
import com.nju.comment.util.MethodRecordUtil;
import com.nju.comment.util.TextProcessUtil;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;

@Slf4j
public record MethodHistoryManager(MethodHistoryRepository repository, Project project) {

    private static final MethodStateMachine STATE_MACHINE = new MethodStateMachine();

    public boolean updateMethodHistoryAsync(MethodRefreshSnapshot snapshot,
                                            BiConsumer<MethodContext, MethodStatus> commentGeneratorAsync,
                                            boolean allowGeneration) {
        if (snapshot == null) {
            return false;
        }
        String path = snapshot.getFilePath();
        String qualifiedName = snapshot.getQualifiedName();
        String signature = snapshot.getSignature();
        if (path == null || qualifiedName == null || qualifiedName.isBlank()
                || signature == null || signature.isBlank()) {
            return false;
        }

        String key = MethodRecordUtil.buildMethodKey(qualifiedName, signature);
        MethodRecord record = repository.findByKey(key);
        MethodValidationResult validationResult = snapshot.getValidationResult();

        if (validationResult != null && !validationResult.isValid()) {
            if (record != null) {
                boolean changed = applyValidation(record, validationResult, path);
                if (changed) {
                    repository.save(record);
                }
                CommentGeneratorClient.cancelForMethod(key, project);
                return changed;
            }
            return false;
        }

        String curComment = TextProcessUtil.processComment(snapshot.getCurrentComment());
        String curMethod = TextProcessUtil.processMethod(snapshot.getCurrentMethod());

        MethodStateContext ctx = new MethodStateContext(record, curMethod, curComment, path, qualifiedName, signature);
        MethodStateResult result = STATE_MACHINE.evaluate(ctx);

        MethodRecord updatedRecord = result.record();
        boolean changed = false;
        if (updatedRecord != null) {
            changed = applyValidation(updatedRecord, validationResult, path) || result.recordChanged() || record == null;
            if (changed) {
                repository.save(updatedRecord);
            }
        }

        log.info("methodKey: {}, status: {}", key, result.state());

        if (result.requiresGeneration()) {
            if (allowGeneration) {
                result.generationContext().ifPresent(methodContext ->
                        result.generationStatus().ifPresent(status ->
                                commentGeneratorAsync.accept(methodContext, status)));
            }
        } else if (result.requiresCancel()) {
            CommentGeneratorClient.cancelForMethod(key, project);
        }

        if (!allowGeneration
                && (MethodStatus.NEW_METHOD_WITH_COMMENT.equals(result.state())
                || MethodStatus.COMMENT_CHANGED.equals(result.state()))) {
            return updateMethodHistoryAsync(snapshot, commentGeneratorAsync, false) || changed;
        }

        return changed;
    }

    private boolean applyValidation(MethodRecord record, MethodValidationResult validationResult, String filePath) {
        boolean changed = false;
        if (filePath != null && !filePath.equals(record.getFilePath())) {
            record.setFilePath(filePath);
            changed = true;
        }
        if (validationResult != null) {
            MethodValidationResult existing = record.getValidationResult();
            boolean same = existing != null
                    && existing.isValid() == validationResult.isValid()
                    && existing.getSourceStamp() == validationResult.getSourceStamp()
                    && TextProcessUtil.safeTrimNullable(existing.getInvalidReason())
                    .equals(TextProcessUtil.safeTrimNullable(validationResult.getInvalidReason()));
            if (!same) {
                record.setValidationResult(validationResult);
                record.touch();
                changed = true;
            }
        }
        return changed;
    }

    public void clearDeletedFileHistories() {
        List<MethodRecord> allRecords = repository.findAll();
        for (MethodRecord record : allRecords) {
            String filePath = record.getFilePath();
            if (filePath == null || filePath.isBlank()) {
                continue;
            }
            VirtualFile vf = LocalFileSystem.getInstance().findFileByPath(filePath);
            if (vf == null || !vf.exists()) {
                deleteByKey(record.getKey());
            }
        }
    }

    public boolean clearMissingMethodHistories(String filePath, Set<String> currentKeys) {
        boolean changed = false;
        List<MethodRecord> allRecords = repository.findAll();
        for (MethodRecord record : allRecords) {
            if (!filePath.equals(record.getFilePath())) {
                continue;
            }
            if (!currentKeys.contains(record.getKey())) {
                deleteByKey(record.getKey());
                changed = true;
            }
        }
        return changed;
    }

    public MethodRecord findByKey(String key) {
        return repository.findByKey(key);
    }

    public void save(MethodRecord record) {
        repository.save(record);
    }

    public void deleteByKey(String key) {
        repository.deleteByKey(key);
    }

    public List<MethodRecord> findAll() {
        return repository.findAll();
    }
}
