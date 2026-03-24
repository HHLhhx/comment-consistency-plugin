package com.nju.comment;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.StartupActivity;
import com.nju.comment.service.PluginApplicationService;
import com.nju.comment.service.PluginProjectService;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;

@Slf4j
public class PluginStartupActivity implements StartupActivity.DumbAware {

    @Override
    public void runActivity(@NotNull Project project) {
        // Ensure application-level services are initialized before project startup logic runs.
        ApplicationManager.getApplication().getService(PluginApplicationService.class);

        PluginProjectService pluginProjectService = project.getService(PluginProjectService.class);
        log.info("Plugin project service initialized: {}", pluginProjectService);
        if (pluginProjectService != null) {
            pluginProjectService.initialize();
        }
    }
}
