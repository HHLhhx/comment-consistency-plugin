package com.nju.comment.history.state.impl;

import com.nju.comment.dto.MethodContext;
import com.nju.comment.dto.MethodRecord;
import com.nju.comment.dto.MethodStatus;
import com.nju.comment.history.state.MethodState;
import com.nju.comment.history.state.MethodStateContext;
import com.nju.comment.history.state.MethodStateResult;

/**
 * 正在生成注释状态
 */
public class GeneratingState implements MethodState {

    /**
     * 匹配正在生成注释场景：<br>
     * 1. 有记录且当前状态为 GENERATING 且未修改方法体和注释<br>
     * 2. NEW_METHOD_WITHOUT_COMMENT 状态下手动触发注释生成自动转为 GENERATING
     *
     * @param context 方法状态上下文
     * @return 是否匹配
     */
    @Override
    public boolean matches(MethodStateContext context) {
        return context.hasRecord()
                    && MethodStatus.GENERATING.equals(context.getCurMethodStatus())
                    && context.commentEqualsOld()
                    && context.methodEqualsStaged();
    }

    /**
     * 处理正在生成注释状态的方法：发送注释生成请求
     *
     * @param context 方法状态上下文
     * @return 方法状态处理结果
     */
    @Override
    public MethodStateResult handle(MethodStateContext context) {
        MethodRecord record = context.getRecord();
        record.setStatus(MethodStatus.GENERATING);
        record.touch();

        MethodContext generationContext = new MethodContext(
                record.getOldMethod(),
                record.getOldComment(),
                context.getCurrentMethod()
        );
        return MethodStateResult.changedWithGeneration(record, MethodStatus.GENERATING, generationContext, MethodStatus.TO_BE_GENERATE);
    }
}
