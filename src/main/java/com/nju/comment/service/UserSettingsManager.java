package com.nju.comment.service;

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.project.Project;

/**
 * 按「用户 + 项目」维度持久化用户设置。
 * <p>
 * 使用 {@link PropertiesComponent#getInstance(Project)} 实现项目级持久化，
 * 以用户名作为 key 前缀实现同项目下的用户隔离。
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
}
