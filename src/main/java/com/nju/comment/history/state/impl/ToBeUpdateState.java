package com.nju.comment.history.state.impl;

import com.nju.comment.dto.MethodRecord;
import com.nju.comment.dto.MethodStatus;
import com.nju.comment.history.state.MethodState;
import com.nju.comment.history.state.MethodStateContext;
import com.nju.comment.history.state.MethodStateResult;

/**
 * 表示新的注释建议已到达，等待用户处理
 */
public final class ToBeUpdateState implements MethodState {

    /**
     * 匹配注释已生成场景：<br>
     * 1. 有记录且当前状态为 TO_BE_UPDATE 且未修改方法体和注释<br>
     * 2. METHOD_CHANGED 状态下注释更新完成自动转为 TO_BE_UPDATE
     *
     * @param context 方法状态上下文
     * @return 是否匹配该状态
     */
    @Override
    public boolean matches(MethodStateContext context) {
        return context.hasRecord()
                    && MethodStatus.TO_BE_UPDATE.equals(context.getCurMethodStatus())
                    && context.commentEqualsOld()
                    && context.methodEqualsStaged();
    }

    /**
     * 处理注释已生成状态的方法：保持现状，等待用户处理<br>
     * 1. onApply：用户接受生成的注释 -> UNCHANGED<br>
     * 2. onIgnore：用户拒绝生成的注释 -> UNCHANGED
     *
     * @param context 方法状态上下文
     * @return 方法状态处理结果
     */
    @Override
    public MethodStateResult handle(MethodStateContext context) {
        MethodRecord record = context.getRecord();
        record.setStatus(MethodStatus.TO_BE_UPDATE);
        return MethodStateResult.unchanged(record, MethodStatus.TO_BE_UPDATE);
    }
}
