package com.nju.comment.history.state;

/**
 * 定义方法历史记录状态机中的单个状态转移处理器。
 */
public interface MethodState {

    /**
     * 判断当前处理器是否能处理提供的上下文。
     */
    boolean matches(MethodStateContext context);

    /**
     * 执行特定状态的转移逻辑。
     */
    MethodStateResult handle(MethodStateContext context);
}
