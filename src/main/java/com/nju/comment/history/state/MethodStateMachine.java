package com.nju.comment.history.state;

import com.nju.comment.dto.MethodRecord;
import com.nju.comment.dto.MethodStatus;
import com.nju.comment.history.state.impl.*;

import java.util.List;

/**
 * 方法状态转移评估的入口点。
 */
public final class MethodStateMachine {

    private final List<MethodState> states;

    public MethodStateMachine() {
        this.states = List.of(
            new NewMethodWithCommentState(),
            new NewMethodWithoutCommentState(),
            new ToBeUpdateState(),
            new ToBeGenerateState(),
            new CommentChangedState(),
            new MethodChangedState(),
            new GeneratingState(),
            new UnchangedState(),
            new UnDefineState()
        );
    }

    /**
     * 评估给定上下文对应的状态转移。
     * 按优先级遍历所有状态处理器，返回第一个匹配的结果。
     */
    public MethodStateResult evaluate(MethodStateContext context) {
        for (MethodState state : states) {
            if (state.matches(context)) {
                return state.handle(context);
            }
        }
        MethodRecord record = context.getRecord();
        return record != null
                ? MethodStateResult.unchanged(record, MethodStatus.UNDEFINED)
                : MethodStateResult.unchanged(null, MethodStatus.UNDEFINED);
    }
}
