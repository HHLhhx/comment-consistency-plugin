package com.nju.comment.service;

import com.intellij.credentialStore.CredentialAttributes;
import com.intellij.credentialStore.Credentials;
import com.intellij.ide.passwordSafe.PasswordSafe;
import com.intellij.openapi.application.ApplicationManager;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * 全局认证状态管理器
 */
@Slf4j
public final class AuthManager {

    private static final String AUTH_SERVICE_NAME = "comment-consistency-plugin-auth";
    private static final CredentialAttributes TOKEN_ATTRIBUTES =
            new CredentialAttributes(AUTH_SERVICE_NAME + ":token");
    private static final CredentialAttributes USERNAME_ATTRIBUTES =
            new CredentialAttributes(AUTH_SERVICE_NAME + ":username");
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
        synchronized (AUTH_LOCK) {
            Credentials tokenCredentials = PasswordSafe.getInstance().get(TOKEN_ATTRIBUTES);
            Credentials usernameCredentials = PasswordSafe.getInstance().get(USERNAME_ATTRIBUTES);
            token = tokenCredentials == null ? null : tokenCredentials.getPasswordAsString();
            username = usernameCredentials == null ? null : usernameCredentials.getPasswordAsString();
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
            PasswordSafe.getInstance().set(TOKEN_ATTRIBUTES, new Credentials("token", newToken));
            PasswordSafe.getInstance().set(USERNAME_ATTRIBUTES, new Credentials("username", newUsername));
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
            PasswordSafe.getInstance().set(TOKEN_ATTRIBUTES, null);
            PasswordSafe.getInstance().set(USERNAME_ATTRIBUTES, null);
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
