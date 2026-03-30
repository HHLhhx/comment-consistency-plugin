package com.nju.comment.pojo;

import com.nju.comment.util.MethodRecordUtil;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public final class MethodRefreshSnapshot {

    private final String filePath;
    private final String qualifiedName;
    private final String signature;
    private final String currentMethod;
    private final String currentComment;
    private final MethodValidationResult validationResult;

    public String getMethodKey() {
        return MethodRecordUtil.buildMethodKey(qualifiedName, signature);
    }
}
