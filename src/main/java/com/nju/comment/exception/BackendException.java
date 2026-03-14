package com.nju.comment.exception;

import lombok.Getter;

/**
 * 后端业务异常的插件端表示。
 * 携带 {@link ErrorCode} 和后端原始消息，供上层统一处理。
 */
@Getter
public class BackendException extends RuntimeException {

    private final ErrorCode errorCode;

    // 后端返回的原始 message
    private final String serverMessage;

    public BackendException(ErrorCode errorCode, String serverMessage) {
        super(serverMessage != null ? serverMessage : errorCode.getDefaultMessage());
        this.errorCode = errorCode;
        this.serverMessage = serverMessage;
    }

    public BackendException(ErrorCode errorCode, String serverMessage, Throwable cause) {
        super(serverMessage != null ? serverMessage : errorCode.getDefaultMessage(), cause);
        this.errorCode = errorCode;
        this.serverMessage = serverMessage;
    }
}
