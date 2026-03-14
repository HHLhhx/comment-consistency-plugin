package com.nju.comment.exception;

import lombok.Getter;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 插件端错误码枚举，与后端 ErrorCode 对齐。
 * 仅定义插件端需要识别和处理的错误码子集，便于扩展。
 */
@Getter
public enum ErrorCode {

    // ---- 系统级 1xxx ----
    SYSTEM_ERROR(1001, "系统内部错误", ErrorLevel.ERROR, false),
    PARAMETER_ERROR(1002, "参数错误", ErrorLevel.WARN, false),
    TIMEOUT_ERROR(1003, "请求超时", ErrorLevel.WARN, true),

    // ---- LLM 2xxx ----
    LLM_TIMEOUT(2002, "LLM调用超时", ErrorLevel.WARN, true),
    LLM_MODEL_FETCH_ERROR(2006, "获取LLM模型列表失败", ErrorLevel.ERROR, false),
    LLM_API_KEY_NOT_SET(2009, "尚未配置 API Key，请在设置中配置", ErrorLevel.WARN, false),
    LLM_API_KEY_INVALID(2010, "API Key 无效", ErrorLevel.ERROR, false),

    // ---- 注释业务 5xxx ----
    COMMENT_SERVICE_ERROR(5001, "注释服务异常", ErrorLevel.ERROR, false),

    // ---- 认证 7xxx ----
    AUTH_LOGIN_FAILED(7001, "用户名或密码错误", ErrorLevel.WARN, false),
    AUTH_USERNAME_EXISTS(7002, "用户名已存在", ErrorLevel.WARN, false),
    AUTH_TOKEN_EXPIRED(7003, "登录已过期，请重新登录", ErrorLevel.WARN, false),
    AUTH_TOKEN_INVALID(7004, "登录凭证无效，请重新登录", ErrorLevel.WARN, false),
    AUTH_NOT_LOGGED_IN(7005, "未登录，请先登录", ErrorLevel.WARN, false),
    AUTH_TOKEN_BLACKLISTED(7008, "令牌已失效，请重新登录", ErrorLevel.WARN, false),
    AUTH_PHONE_EXISTS(7009, "手机号已被注册", ErrorLevel.WARN, false),

    // ---- 兜底：未知错误码 ----
    UNKNOWN(-1, "未知错误", ErrorLevel.ERROR, false);

    private final int code;
    private final String defaultMessage;
    private final ErrorLevel level;

    // 是否允许用户重试
    private final boolean retryable;

    ErrorCode(int code, String defaultMessage, ErrorLevel level, boolean retryable) {
        this.code = code;
        this.defaultMessage = defaultMessage;
        this.level = level;
        this.retryable = retryable;
    }

    /** 错误级别，决定日志级别和通知方式 */
    public enum ErrorLevel {
        WARN, ERROR
    }

    // ---- code → enum 快速查找 ----
    private static final Map<Integer, ErrorCode> CODE_MAP =
            Stream.of(values())
                    .filter(e -> e != UNKNOWN)
                    .collect(Collectors.toMap(ErrorCode::getCode, Function.identity()));

    /**
     * 根据后端返回的整型 code 解析为枚举，未匹配时返回 UNKNOWN
     */
    public static ErrorCode fromCode(int code) {
        return CODE_MAP.getOrDefault(code, UNKNOWN);
    }
}
