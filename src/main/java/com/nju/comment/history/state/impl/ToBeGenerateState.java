package com.nju.comment.history.state.impl;

import com.nju.comment.dto.MethodRecord;
import com.nju.comment.dto.MethodStatus;
import com.nju.comment.history.state.MethodState;
import com.nju.comment.history.state.MethodStateContext;
import com.nju.comment.history.state.MethodStateResult;
import com.nju.comment.util.TextProcessUtil;

/**
 * 表示新方法的注释已生成，等待用户处理。
 */
public final class ToBeGenerateState implements MethodState {

    @Override
    public boolean matches(MethodStateContext context) {
        return context.hasRecord()
                    && MethodStatus.TO_BE_GENERATE.equals(context.getCurMethodStatus())
                    && context.commentEqualsOld()
                    && context.methodEqualsStaged()
                    && context.getRecord().getTag() == 4;
    }

    /**
     * 仅保持记录同步，等待用户点击工具栏按钮。
     */
    @Override
    public MethodStateResult handle(MethodStateContext context) {
        MethodRecord record = context.getRecord();
        record.setStatus(MethodStatus.TO_BE_GENERATE);
        record.setTag(4);
        return MethodStateResult.unchanged(record, MethodStatus.TO_BE_GENERATE);
    }
}
