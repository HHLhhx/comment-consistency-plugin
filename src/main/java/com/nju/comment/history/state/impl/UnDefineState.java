package com.nju.comment.history.state.impl;

import com.nju.comment.dto.MethodStatus;
import com.nju.comment.history.state.MethodState;
import com.nju.comment.history.state.MethodStateContext;
import com.nju.comment.history.state.MethodStateResult;
import lombok.extern.slf4j.Slf4j;

/**
 * 未定义状态，作为兜底状态使用
 */
@Slf4j
public final class UnDefineState implements MethodState {
    @Override
    public boolean matches(MethodStateContext context) {
        log.warn("UnDefined state matched for method: {}", context.getSignature());
        return true;
    }

    @Override
    public MethodStateResult handle(MethodStateContext context) {
        log.warn("UnDefined state handled for method: {}", context.getSignature());
        return MethodStateResult.unchanged(context.getRecord(), MethodStatus.UNDEFINED);
    }
}
