package com.nju.comment.history.state;

import com.nju.comment.dto.MethodContext;
import com.nju.comment.dto.MethodRecord;
import com.nju.comment.dto.MethodStatus;

import java.util.Optional;

/**
 * 表示状态转移评估的结果。
 */
public final class MethodStateResult {

    private final MethodRecord record;
    private final MethodStatus state;
    private final MethodContext generationContext;
    private final MethodStatus generationStatus;
    private final boolean recordChanged;
    private final boolean cancelRequested;

    private MethodStateResult(MethodRecord record,
                              MethodStatus state,
                              MethodContext generationContext,
                              MethodStatus generationStatus,
                              boolean recordChanged,
                              boolean cancelRequested) {
        this.record = record;
        this.state = state;
        this.generationContext = generationContext;
        this.generationStatus = generationStatus;
        this.recordChanged = recordChanged;
        this.cancelRequested = cancelRequested;
    }

    /**
     * 创建未变更的结果。
     */
    public static MethodStateResult unchanged(MethodRecord record, MethodStatus state) {
        return new MethodStateResult(record, state, null, null, false, false);
    }

    /**
     * 创建已变更的结果。
     */
    public static MethodStateResult changed(MethodRecord record, MethodStatus state) {
        return new MethodStateResult(record, state, null, null, true, false);
    }

    /**
     * 创建已变更且需要生成注释的结果。
     */
    public static MethodStateResult changedWithGeneration(MethodRecord record,
                                                          MethodStatus state,
                                                          MethodContext context,
                                                          MethodStatus generationStatus) {
        return new MethodStateResult(record, state, context, generationStatus, true, false);
    }

    /**
     * 创建已变更且需要取消在途请求的结果。
     */
    public static MethodStateResult changedWithCancel(MethodRecord record, MethodStatus state) {
        return new MethodStateResult(record, state, null, null, true, true);
    }

    public MethodRecord record() {
        return record;
    }

    public MethodStatus state() {
        return state;
    }

    public Optional<MethodContext> generationContext() {
        return Optional.ofNullable(generationContext);
    }

    public Optional<MethodStatus> generationStatus() {
        return Optional.ofNullable(generationStatus);
    }

    public boolean recordChanged() {
        return recordChanged;
    }

    /**
     * 判断是否需要触发异步注释生成。
     */
    public boolean requiresGeneration() {
        return generationContext != null && generationStatus != null;
    }

    /**
     * 判断是否需要取消在途的注释生成请求。
     */
    public boolean requiresCancel() {
        return cancelRequested;
    }
}
