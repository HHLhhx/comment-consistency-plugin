package com.nju.comment.history.state.impl;

import com.nju.comment.dto.MethodRecord;
import com.nju.comment.dto.MethodStatus;
import com.nju.comment.history.state.MethodState;
import com.nju.comment.history.state.MethodStateContext;
import com.nju.comment.history.state.MethodStateResult;

/**
 * 处理开发者对注释内容的手动修改
 */
public final class CommentChangedState implements MethodState {

    /**
     * 匹配开发者手动修改注释的场景：<br>
     * 1. 已有记录，当前有注释且与旧注释不同<br>
     * 2. 方法状态不是 NEW_METHOD_WITHOUT_COMMENT、GENERATING 或 TO_BE_GENERATE
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
                    && context.hasCurrentComment()
                    && !context.commentEqualsOld();
    }

    /**
     * 处理开发者手动修改注释的场景：<br>
     * 1. 更新旧注释和方法体，清空暂存注释<br>
     * 2. 原 METHOD_CHANGED 状态需取消在途请求
     *
     * @param context 方法状态上下文
     * @return 方法状态处理结果
     */
    @Override
    public MethodStateResult handle(MethodStateContext context) {
        MethodRecord record = context.getRecord();
        record.setOldComment(context.getCurrentComment());
        record.setOldMethod(context.getCurrentMethod());
        record.setStagedMethod(context.getCurrentMethod());
        record.clearStagedComment();
        record.touch();

        if (MethodStatus.METHOD_CHANGED.equals(context.getCurMethodStatus())) {
            // 从 METHOD_CHANGED 转为 COMMENT_CHANGED，说明在方法变更后用户修改了注释，取消之前在途请求
            record.setStatus(MethodStatus.COMMENT_CHANGED);
            return MethodStateResult.changedWithCancel(record, MethodStatus.COMMENT_CHANGED);
        }

        // 正常从其他状态转为 COMMENT_CHANGED
        record.setStatus(MethodStatus.COMMENT_CHANGED);
        return MethodStateResult.changed(record, MethodStatus.COMMENT_CHANGED);
    }
}
