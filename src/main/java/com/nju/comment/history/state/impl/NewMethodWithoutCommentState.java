package com.nju.comment.history.state.impl;

import com.nju.comment.dto.MethodRecord;
import com.nju.comment.dto.MethodStatus;
import com.nju.comment.history.state.MethodState;
import com.nju.comment.history.state.MethodStateContext;
import com.nju.comment.history.state.MethodStateResult;

/**
 * 处理无注释的方法
 */
public final class NewMethodWithoutCommentState implements MethodState {

    /**
     * 匹配无注释的新方法场景:<br>
     * 1. 之前没有记录且当前无注释<br>
     * 2. GENERATING 和 TO_BE_GENERATE 状态下仅方法被修改<br>
     * 3. 其他状态下当前注释为空
     *
     * @param context 方法状态上下文
     * @return 是否匹配该状态
     */
    @Override
    public boolean matches(MethodStateContext context) {
        return (!context.hasCurrentComment()
                    && (!context.hasRecord()
                        || (!MethodStatus.GENERATING.equals(context.getCurMethodStatus())
                            && !MethodStatus.TO_BE_GENERATE.equals(context.getCurMethodStatus()))))
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
     * 处理到达此状态的方法:<br>
     * 1. 全新无注释方法，创建记录<br>
     * 2. 如果已有记录，更新方法体，清空旧注释和暂存注释<br>
     * 3. 原 GENERATING 和 METHOD_CHANGED 状态需取消在途请求
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
            record.setOldComment("");
            record.setStagedMethod(context.getCurrentMethod());
            record.clearStagedComment();

            if (MethodStatus.GENERATING.equals(context.getCurMethodStatus())
                    || MethodStatus.METHOD_CHANGED.equals(context.getCurMethodStatus())) {
                // 从 GENERATING 或 METHOD_CHANGED 转为 NEW_METHOD_WITHOUT_COMMENT，说明在生成过程中用户修改了方法体，取消前述请求
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
            record.touch();
            return MethodStateResult.changed(record, MethodStatus.NEW_METHOD_WITHOUT_COMMENT);
        }
    }
}
