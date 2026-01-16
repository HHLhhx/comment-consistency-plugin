package com.nju.comment.service;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.components.Service;
import com.nju.comment.client.global.CommentGeneratorClient;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public final class PluginProjectService implements Disposable {

    private static final String DEFAULT_BASE_URL = "http://localhost:8080/api";

    public void initialize() {
        log.info("项目启动初始化");
        CommentGeneratorClient.init(DEFAULT_BASE_URL);
        CommentGeneratorClient.getAvailableModels();
    }

    @Override
    public void dispose() {
        log.info("项目关闭，释放资源");
        CommentGeneratorClient.shutdown();
    }
}
