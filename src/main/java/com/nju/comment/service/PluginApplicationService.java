package com.nju.comment.service;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.components.Service;
import com.nju.comment.client.global.CommentGeneratorClient;
import lombok.extern.slf4j.Slf4j;

/**
 * 应用级服务：在 IDE 退出时释放全局静态资源（HTTP 客户端线程池等）。
 * <p>
 * IntelliJ 会在应用关闭时自动调用 {@link #dispose()}。
 */
@Slf4j
@Service(Service.Level.APP)
public final class PluginApplicationService implements Disposable {

    @Override
    public void dispose() {
        log.info("IDE 退出，释放全局资源");
        CommentGeneratorClient.shutdown();
    }
}
