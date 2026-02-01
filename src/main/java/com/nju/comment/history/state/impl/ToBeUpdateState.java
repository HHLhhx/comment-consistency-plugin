package com.nju.comment.history.state.impl;

import com.nju.comment.dto.MethodRecord;
import com.nju.comment.dto.MethodStatus;
import com.nju.comment.history.state.MethodState;
import com.nju.comment.history.state.MethodStateContext;
import com.nju.comment.history.state.MethodStateResult;
import com.nju.comment.util.TextProcessUtil;

/**
 * 表示新的注释建议已到达，等待用户应用或忽略。
 */
public final class ToBeUpdateState implements MethodState {

    @Override
    public boolean matches(MethodStateContext context) {
        return context.hasRecord()
                    && MethodStatus.TO_BE_UPDATE.equals(context.getCurMethodStatus())
                    && context.commentEqualsOld()
                    && context.methodEqualsStaged()
                    && context.getStagedComment() != null
                    && !context.getStagedComment().isEmpty()
                    && context.getRecord().getTag() == 1;
    }

    /**
     * 保持记录原样。
     */
    @Override
    public MethodStateResult handle(MethodStateContext context) {
        MethodRecord record = context.getRecord();
        record.setStatus(MethodStatus.TO_BE_UPDATE);
        record.setTag(1);
        return MethodStateResult.unchanged(record, MethodStatus.TO_BE_UPDATE);
    }
}
