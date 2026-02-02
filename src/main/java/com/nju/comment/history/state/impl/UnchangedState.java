package com.nju.comment.history.state.impl;

import com.nju.comment.dto.MethodRecord;
import com.nju.comment.dto.MethodStatus;
import com.nju.comment.history.state.MethodState;
import com.nju.comment.history.state.MethodStateContext;
import com.nju.comment.history.state.MethodStateResult;

/**
 * 未检测到有意义的变化时的状态
 */
public final class UnchangedState implements MethodState {

    /**
     * 匹配未检测到有意义变化的场景：<br>
     * 1. 已有记录且注释和方法体均未变化<br>
     * 2. 仅支持原 COMMENT_CHANGED、UNCHANGED 或 NEW_METHOD_WITH_COMMENT 状态
     *
     * @param context 方法状态上下文
     * @return 是否匹配该状态
     */
    @Override
    public boolean matches(MethodStateContext context) {
        return context.hasRecord()
                    && (MethodStatus.COMMENT_CHANGED.equals(context.getCurMethodStatus())
                        || MethodStatus.UNCHANGED.equals(context.getCurMethodStatus())
                        || MethodStatus.NEW_METHOD_WITH_COMMENT.equals(context.getCurMethodStatus()))
                    && context.commentEqualsOld()
                    && context.methodEqualsStaged();
    }

    /**
     * 处理未检测到有意义变化的场景：不做特殊处理
     *
     * @param context 方法状态上下文
     * @return 方法状态处理结果
     */
    @Override
    public MethodStateResult handle(MethodStateContext context) {
        MethodRecord record = context.getRecord();
        if (MethodStatus.UNCHANGED.equals(context.getCurMethodStatus())) {
            // 无变化
            return MethodStateResult.unchanged(record, MethodStatus.UNCHANGED);
        } else {
            // 恢复到未变化状态
            record.setStatus(MethodStatus.UNCHANGED);
            return MethodStateResult.changed(record, MethodStatus.UNCHANGED);
        }
    }
}
