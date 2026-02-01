package com.nju.comment.history.state.impl;

import com.nju.comment.dto.MethodContext;
import com.nju.comment.dto.MethodRecord;
import com.nju.comment.dto.MethodStatus;
import com.nju.comment.history.state.MethodState;
import com.nju.comment.history.state.MethodStateContext;
import com.nju.comment.history.state.MethodStateResult;

/**
 * 处理方法实现改变但注释保持不变的场景。
 */
public final class MethodChangedState implements MethodState {

    @Override
    public boolean matches(MethodStateContext context) {
        return context.hasRecord()
                    && !MethodStatus.NEW_METHOD_WITHOUT_COMMENT.equals(context.getCurMethodStatus())
                    && !MethodStatus.GENERATING.equals(context.getCurMethodStatus())
                    && !MethodStatus.TO_BE_GENERATE.equals(context.getCurMethodStatus())
                    && (!context.methodEqualsStaged()
                        || MethodStatus.METHOD_CHANGED.equals(context.getCurMethodStatus()))
                    && context.commentEqualsOld();
    }

    /**
     * 方法实现改变，需要重新生成注释建议。
     * 返回 TO_BE_UPDATE 状态，通知后端生成更新内容。
     */
    @Override
    public MethodStateResult handle(MethodStateContext context) {
        MethodRecord record = context.getRecord();
        record.setStagedMethod(context.getCurrentMethod());
        record.clearStagedComment();
        record.setStatus(MethodStatus.METHOD_CHANGED);
        record.setTag(0);
        record.touch();

        MethodContext generationContext = new MethodContext(
                record.getOldMethod(),
                record.getOldComment(),
                context.getCurrentMethod()
        );
        return MethodStateResult.changedWithGeneration(record, MethodStatus.METHOD_CHANGED, generationContext, MethodStatus.TO_BE_UPDATE);
    }
}
