package com.nju.comment.history.state.impl;

import com.nju.comment.dto.MethodRecord;
import com.nju.comment.dto.MethodStatus;
import com.nju.comment.history.state.MethodState;
import com.nju.comment.history.state.MethodStateContext;
import com.nju.comment.history.state.MethodStateResult;

/**
 * 未检测到有意义的变化时的默认回退状态。
 * 优先级最低。
 */
public final class UnchangedState implements MethodState {

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
     * 保持暂存方法体与当前方法同步。
     */
    @Override
    public MethodStateResult handle(MethodStateContext context) {
        MethodRecord record = context.getRecord();
        record.setTag(0);
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
