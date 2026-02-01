package com.nju.comment.history.state.impl;

import com.nju.comment.dto.MethodRecord;
import com.nju.comment.dto.MethodStatus;
import com.nju.comment.history.state.MethodState;
import com.nju.comment.history.state.MethodStateContext;
import com.nju.comment.history.state.MethodStateResult;

/**
 * 处理已包含注释的全新方法。
 */
public final class NewMethodWithCommentState implements MethodState {

    @Override
    public boolean matches(MethodStateContext context) {
        return (!context.hasRecord()
                    && context.hasCurrentComment())
                || (context.hasRecord()
                    && MethodStatus.NEW_METHOD_WITHOUT_COMMENT.equals(context.getCurMethodStatus())
                    && context.hasCurrentComment())
                || (context.hasRecord()
                    && MethodStatus.GENERATING.equals(context.getCurMethodStatus())
                    && context.hasCurrentComment())
                || (context.hasRecord()
                    && MethodStatus.TO_BE_GENERATE.equals(context.getCurMethodStatus())
                    && context.hasCurrentComment());
    }

    /**
     * 为新方法创建历史记录，使用当前的方法体和注释。
     * 不需要生成注释，设置 tag=0 表示正常跟踪。
     */
    @Override
    public MethodStateResult handle(MethodStateContext context) {
        MethodRecord record;
        if (context.hasRecord()) {
            record = context.getRecord();
            record.setOldMethod(context.getCurrentComment());
            record.setOldComment(context.getCurrentComment());
            record.setStagedMethod(context.getCurrentMethod());
            record.clearStagedComment();
            record.setTag(0);
            record.touch();

            if (MethodStatus.GENERATING.equals(context.getCurMethodStatus())) {
                // 从 GENERATING 转为 NEW_METHOD_WITH_COMMENT，说明在生成过程中用户已经手动添加了注释，取消前述请求
                record.setStatus(MethodStatus.NEW_METHOD_WITH_COMMENT);
                return MethodStateResult.changedWithCancel(record, MethodStatus.NEW_METHOD_WITH_COMMENT);
            } else {
                // 从 TO_BE_GENERATE 或 NEW_METHOD_WITHOUT_COMMENT 转为 NEW_METHOD_WITH_COMMENT
                record.setStatus(MethodStatus.NEW_METHOD_WITH_COMMENT);
                return MethodStateResult.changed(record, MethodStatus.NEW_METHOD_WITH_COMMENT);
            }
        } else {
            // 全新带注释方法
            record = new MethodRecord(
                    context.getQualifiedName(),
                    context.getSignature(),
                    context.getCurrentMethod(),
                    context.getCurrentComment()
            );
            context.ensurePointer(record);
            context.syncFilePath(record);
            record.setStagedMethod(context.getCurrentMethod());
            record.clearStagedComment();
            record.setStatus(MethodStatus.NEW_METHOD_WITH_COMMENT);
            record.setTag(0);
            record.touch();
            return MethodStateResult.changed(record, MethodStatus.NEW_METHOD_WITH_COMMENT);
        }
    }
}
