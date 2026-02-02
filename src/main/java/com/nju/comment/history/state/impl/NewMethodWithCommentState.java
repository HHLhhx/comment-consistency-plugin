package com.nju.comment.history.state.impl;

import com.nju.comment.dto.MethodRecord;
import com.nju.comment.dto.MethodStatus;
import com.nju.comment.history.state.MethodState;
import com.nju.comment.history.state.MethodStateContext;
import com.nju.comment.history.state.MethodStateResult;

/**
 * 处理已包含注释的新方法
 */
public final class NewMethodWithCommentState implements MethodState {

    /**
     * 匹配已包含注释的新方法场景：<br>
     * 1. 之前没有记录且当前有注释<br>
     * 2. NEW_METHOD_WITHOUT_COMMENT、GENERATING 或 TO_BE_GENERATE 状态下添加了注释<br>
     * 3. TO_BE_GENERATE 状态下应用了生成的注释
     *
     * @param context 方法状态上下文
     * @return 是否匹配该状态
     */
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
     * 处理到达此状态的方法：<br>
     * 1. 全新带注释方法，创建记录<br>
     * 2. 如果已有记录，更新方法体和注释，清空暂存注释<br>
     * 3. 原 GENERATING 状态需取消在途请求
     *
     * @param context 方法状态上下文
     * @return 方法状态处理结果
     */
    @Override
    public MethodStateResult handle(MethodStateContext context) {
        MethodRecord record;
        if (context.hasRecord()) {
            record = context.getRecord();
            record.setOldMethod(context.getCurrentMethod());
            record.setOldComment(context.getCurrentComment());
            record.setStagedMethod(context.getCurrentMethod());
            record.clearStagedComment();
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
            record.touch();
            return MethodStateResult.changed(record, MethodStatus.NEW_METHOD_WITH_COMMENT);
        }
    }
}
