package com.nju.comment.service;

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.application.ApplicationManager;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * 全局认证状态管理器
 */
@Slf4j
public final class AuthManager {

    private static final String TOKEN_KEY = "comment.consistency.auth.token";
    private static final String USERNAME_KEY = "comment.consistency.auth.username";
    private static final Object AUTH_LOCK = new Object();

    @Getter
    private static volatile String token;
    @Getter
    private static volatile String username;

    private AuthManager() {
    }

    /**
     * 从持久化存储恢复登录状态（插件启动时调用）
     */
    public static void init() {
        PropertiesComponent props = PropertiesComponent.getInstance();
        synchronized (AUTH_LOCK) {
            token = props.getValue(TOKEN_KEY);
            username = props.getValue(USERNAME_KEY);
        }
        if (token != null) {
            log.info("已恢复登录状态: username={}", username);
        }
    }

    /**
     * 保存登录凭证
     */
    public static void saveAuth(String newToken, String newUsername) {
        synchronized (AUTH_LOCK) {
            token = newToken;
            username = newUsername;
            PropertiesComponent props = PropertiesComponent.getInstance();
            props.setValue(TOKEN_KEY, newToken);
            props.setValue(USERNAME_KEY, newUsername);
        }
        log.info("已保存登录状态: username={}", newUsername);
        notifyAuthChanged();
    }

    /**
     * 清除登录凭证（登出或 token 失效时调用）
     */
    public static void clearAuth() {
        synchronized (AUTH_LOCK) {
            token = null;
            username = null;
            PropertiesComponent props = PropertiesComponent.getInstance();
            props.unsetValue(TOKEN_KEY);
            props.unsetValue(USERNAME_KEY);
        }
        log.info("已清除登录状态");
        notifyAuthChanged();
    }

    /**
     * 检查当前是否有有效的登录状态
     */
    public static boolean isLoggedIn() {
        return token != null && !token.isBlank();
    }

    /**
     * 通过应用级服务广播认证状态变化。
     */
    private static void notifyAuthChanged() {
        PluginApplicationService appService = ApplicationManager.getApplication()
                .getService(PluginApplicationService.class);
        if (appService != null) {
            appService.publishAuthStateChanged();
        }
    }
}
