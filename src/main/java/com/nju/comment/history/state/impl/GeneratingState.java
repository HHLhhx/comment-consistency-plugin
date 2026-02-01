package com.nju.comment.history.state.impl;

import com.nju.comment.dto.MethodContext;
import com.nju.comment.dto.MethodRecord;
import com.nju.comment.dto.MethodStatus;
import com.nju.comment.history.state.MethodState;
import com.nju.comment.history.state.MethodStateContext;
import com.nju.comment.history.state.MethodStateResult;

public class GeneratingState implements MethodState {

    @Override
    public boolean matches(MethodStateContext context) {
        return context.hasRecord()
                    && MethodStatus.GENERATING.equals(context.getCurMethodStatus())
                    && context.commentEqualsOld()
                    && context.methodEqualsStaged();
    }

    @Override
    public MethodStateResult handle(MethodStateContext context) {
        MethodRecord record = context.getRecord();
        record.setStatus(MethodStatus.GENERATING);
        record.setTag(3);
        record.touch();

        MethodContext generationContext = new MethodContext(
                record.getOldMethod(),
                record.getOldComment(),
                context.getCurrentMethod()
        );
        return MethodStateResult.changedWithGeneration(record, MethodStatus.GENERATING, generationContext, MethodStatus.TO_BE_GENERATE);
    }
}
