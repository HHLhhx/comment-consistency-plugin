package com.nju.comment.service;

import com.intellij.ide.util.PropertiesComponent;

/**
 * 按「用户（IDE 全局）」维度持久化用户设置。
 * 以用户名作为 key 前缀实现多用户隔离。
 */
public class UserSettingsManager {

    private static final String PREFIX = "comment.consistency.settings.";

    private final PropertiesComponent props;
    private final String keyPrefix;

    public UserSettingsManager(String username) {
        this.props = PropertiesComponent.getInstance();
        this.keyPrefix = PREFIX + username + ".";
    }

    public String getSelectedModel() {
        return props.getValue(keyPrefix + "model");
    }

    public void setSelectedModel(String model) {
        props.setValue(keyPrefix + "model", model);
    }

    public boolean isRagEnabled() {
        return props.getBoolean(keyPrefix + "rag", false);
    }

    public void setRagEnabled(boolean enabled) {
        props.setValue(keyPrefix + "rag", enabled, false);
    }

    public int getRagExampleNum() {
        int value = props.getInt(keyPrefix + "ragExampleNum", 3);
        return Math.max(1, Math.min(5, value));
    }

    public void setRagExampleNum(int count) {
        int normalized = Math.max(1, Math.min(5, count));
        props.setValue(keyPrefix + "ragExampleNum", normalized, 3);
    }

    public boolean isAutoUpdateEnabled() {
        return props.getBoolean(keyPrefix + "autoUpdate", false);
    }

    public void setAutoUpdateEnabled(boolean enabled) {
        props.setValue(keyPrefix + "autoUpdate", enabled, false);
    }

    public boolean isShowFullApiKeyEnabled() {
        return props.getBoolean(keyPrefix + "showFullApiKey", false);
    }

    public void setShowFullApiKeyEnabled(boolean enabled) {
        props.setValue(keyPrefix + "showFullApiKey", enabled, false);
    }

    public boolean isApiKeyConfiguredHint() {
        return props.getBoolean(keyPrefix + "apiKeyConfigured", false);
    }

    public void setApiKeyConfiguredHint(boolean configured) {
        props.setValue(keyPrefix + "apiKeyConfigured", configured, false);
    }
}
