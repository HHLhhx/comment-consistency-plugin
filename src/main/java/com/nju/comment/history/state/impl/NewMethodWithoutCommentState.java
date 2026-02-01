package com.nju.comment.history.state.impl;

import com.nju.comment.dto.MethodRecord;
import com.nju.comment.dto.MethodStatus;
import com.nju.comment.history.state.MethodState;
import com.nju.comment.history.state.MethodStateContext;
import com.nju.comment.history.state.MethodStateResult;

/**
 * 处理无注释的全新方法，并触发异步注释生成。
 */
public final class NewMethodWithoutCommentState implements MethodState {

    @Override
    public boolean matches(MethodStateContext context) {
        return (!context.hasCurrentComment()
                    && (!context.hasRecord()
                        || (!MethodStatus.GENERATING.equals(context.getCurMethodStatus())
                            && !MethodStatus.TO_BE_GENERATE.equals(context.getCurMethodStatus())
                            && !MethodStatus.NEW_METHOD_WITHOUT_COMMENT.equals(context.getCurMethodStatus()))))
                || (context.hasRecord()
                    && MethodStatus.NEW_METHOD_WITHOUT_COMMENT.equals(context.getCurMethodStatus())
                    && context.commentEqualsOld()
                    && context.getRecord().getTag() == 2)
                || (context.hasRecord()
                    && MethodStatus.GENERATING.equals(context.getCurMethodStatus())
                    && context.commentEqualsOld()
                    && !context.methodEqualsStaged())
                || (context.hasRecord()
                    && MethodStatus.TO_BE_GENERATE.equals(context.getCurMethodStatus())
                    && context.commentEqualsOld()
                    && !context.methodEqualsStaged());
    }

    /**
     * 为新方法创建历史记录，设置 tag=2 表示待处理新建。
     * 不主动触发生成，等待用户手动操作。
     */
    @Override
    public MethodStateResult handle(MethodStateContext context) {
        MethodRecord record;
        if (context.hasRecord()) {
            record = context.getRecord();
            record.setOldMethod(context.getCurrentMethod());
            record.setOldComment("");
            record.setStagedMethod(context.getCurrentMethod());
            record.clearStagedComment();
            record.setTag(2);

            if (MethodStatus.GENERATING.equals(context.getCurMethodStatus())
                    || MethodStatus.TO_BE_GENERATE.equals(context.getCurMethodStatus())
                    || MethodStatus.METHOD_CHANGED.equals(context.getCurMethodStatus())) {
                // 从 GENERATING 或 TO_BE_GENERATE 或 METHOD_CHANGED 转为 NEW_METHOD_WITHOUT_COMMENT，说明在生成过程中用户修改了方法体，取消前述请求
                record.setStatus(MethodStatus.NEW_METHOD_WITHOUT_COMMENT);
                return MethodStateResult.changedWithCancel(record, MethodStatus.NEW_METHOD_WITHOUT_COMMENT);
            } else if (MethodStatus.NEW_METHOD_WITHOUT_COMMENT.equals(context.getCurMethodStatus())) {
                // 状态保持不变
                return MethodStateResult.unchanged(record, MethodStatus.NEW_METHOD_WITHOUT_COMMENT);
            } else {
                // 从 其他 转为 NEW_METHOD_WITHOUT_COMMENT
                record.setStatus(MethodStatus.NEW_METHOD_WITHOUT_COMMENT);
                return MethodStateResult.changed(record, MethodStatus.NEW_METHOD_WITHOUT_COMMENT);
            }
        } else {
            // 全新无注释方法
            record = new MethodRecord(
                    context.getQualifiedName(),
                    context.getSignature(),
                    context.getCurrentMethod(),
                    ""
            );
            context.ensurePointer(record);
            context.syncFilePath(record);
            record.setStagedMethod(context.getCurrentMethod());
            record.clearStagedComment();
            record.setStatus(MethodStatus.NEW_METHOD_WITHOUT_COMMENT);
            record.setTag(2);
            record.touch();
            return MethodStateResult.changed(record, MethodStatus.NEW_METHOD_WITHOUT_COMMENT);
        }
    }
}
