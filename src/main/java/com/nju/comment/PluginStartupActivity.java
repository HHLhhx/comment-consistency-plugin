package com.nju.comment;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.ProjectActivity;
import com.nju.comment.service.PluginProjectService;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

@Slf4j
public class PluginStartupActivity implements ProjectActivity {

    @Override
    public @Nullable Object execute(@NotNull Project project, @NotNull Continuation<? super Unit> continuation) {
        PluginProjectService pluginProjectService = project.getService(PluginProjectService.class);
        log.info("执行插件启动活动，初始化项目服务: {}", pluginProjectService);
        if (pluginProjectService != null) {
            pluginProjectService.initialize();
        }
        return null;
    }
}
