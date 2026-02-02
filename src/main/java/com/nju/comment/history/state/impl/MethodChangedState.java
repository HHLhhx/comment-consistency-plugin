package com.nju.comment.history.state.impl;

import com.nju.comment.dto.MethodContext;
import com.nju.comment.dto.MethodRecord;
import com.nju.comment.dto.MethodStatus;
import com.nju.comment.history.state.MethodState;
import com.nju.comment.history.state.MethodStateContext;
import com.nju.comment.history.state.MethodStateResult;

/**
 * 处理方法改变但注释保持不变的场景
 */
public final class MethodChangedState implements MethodState {

    /**
     * 匹配条件：<br>
     * 1. 有历史记录，且方法体改变但注释未改变<br>
     * 2. 当前状态不是 NEW_METHOD_WITHOUT_COMMENT、GENERATING、TO_BE_GENERATE<br>
     * 3. 当前状态为 METHOD_CHANGED 时不考虑方法体未改变的情况
     *
     * @param context 方法状态上下文
     * @return 是否匹配该状态
     */
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
     * 处理方法改变但注释保持不变的场景:<br>
     * 1. 更新记录中的暂存方法体为当前方法体，清空暂存注释<br>
     * 2. 如果方法体与旧方法体不同，设置状态为 METHOD_CHANGED，创建生成请求<br>
     * 3. 如果方法体与旧方法体相同且当前状态为 METHOD_CHANGED，说明用户恢复了原始方法实现，取消前述请求<br>
     * 4. 否则设置状态为 UNCHANGED
     *
     * @param context 方法状态上下文
     * @return 方法状态处理结果
     */
    @Override
    public MethodStateResult handle(MethodStateContext context) {
        MethodRecord record = context.getRecord();
        record.setStagedMethod(context.getCurrentMethod());
        record.clearStagedComment();

        if (!context.methodEqualsOld()) {
            record.setStatus(MethodStatus.METHOD_CHANGED);
            record.touch();

            MethodContext generationContext = new MethodContext(
                    record.getOldMethod(),
                    record.getOldComment(),
                    context.getCurrentMethod()
            );
            return MethodStateResult.changedWithGeneration(record, MethodStatus.METHOD_CHANGED, generationContext, MethodStatus.TO_BE_UPDATE);
        } else {
            record.touch();

            if (MethodStatus.METHOD_CHANGED.equals(context.getCurMethodStatus())) {
                // 从 METHOD_CHANGED 转为 UNCHANGED，说明在生成过程中用户恢复了原始方法实现，取消前述请求
                record.setStatus(MethodStatus.UNCHANGED);
                return MethodStateResult.changedWithCancel(record, MethodStatus.UNCHANGED);
            }

            record.setStatus(MethodStatus.UNCHANGED);
            return MethodStateResult.changed(record, MethodStatus.UNCHANGED);
        }

    }
}
