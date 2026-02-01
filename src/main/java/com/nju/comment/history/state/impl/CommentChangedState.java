package com.nju.comment.history.state.impl;

import com.nju.comment.dto.MethodRecord;
import com.nju.comment.dto.MethodStatus;
import com.nju.comment.history.state.MethodState;
import com.nju.comment.history.state.MethodStateContext;
import com.nju.comment.history.state.MethodStateResult;

/**
 * 处理开发者对注释内容的手动修改。
 */
public final class CommentChangedState implements MethodState {

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
     * 将手动修改后的注释记录为最新版本，清除暂存内容，恢复正常跟踪。
     */
    @Override
    public MethodStateResult handle(MethodStateContext context) {
        MethodRecord record = context.getRecord();
        record.setOldComment(context.getCurrentComment());
        record.setOldMethod(context.getCurrentMethod());
        record.setStagedMethod(context.getCurrentMethod());
        record.clearStagedComment();
        record.setTag(0);
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
