package com.nju.comment.client.global;

import com.nju.comment.dto.GenerateOptions;
import com.nju.comment.dto.MethodContext;
import com.nju.comment.dto.request.CommentRequest;
import com.nju.comment.dto.response.CommentResponse;
import com.nju.comment.client.PluginCommentClient;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Slf4j
public class CommentGeneratorClient {

    private static volatile PluginCommentClient client;
    private static final Object LOCK = new Object();
    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    @Getter
    private static List<String> modelsList;

    @Getter
    private static volatile String selectedModel = null;

    public static void init(String baseUrl) {
        if (client != null) {
            log.info("CommentGeneratorClient 已完成初始化");
            return;
        }
        synchronized (LOCK) {
            if (client != null) {
                log.info("CommentGeneratorClient 已完成初始化");
                return;
            }
            log.info("CommentGeneratorClient 开始初始化");
            PluginCommentClient.Builder clientBuilder = PluginCommentClient.builder();
            if (baseUrl != null && !baseUrl.isEmpty()) {
                clientBuilder.baseUrl(baseUrl);
            } else {
                clientBuilder.baseUrl("http://localhost:8080/api");
            }
            clientBuilder.requestTimeout(TIMEOUT)
                    .threadPoolSize(10)
                    .maxConcurrentRequests(20);
            client = clientBuilder.build();
            log.info("CommentGeneratorClient 初始化成功");
        }
    }

    public static String generateComment(MethodContext data, GenerateOptions options) {
        if (client == null) {
            log.info("CommentGeneratorClient 未初始化，正在初始化默认配置");
            init(null);
        }
        try {
            log.info("开始生成注释");
            CommentRequest req = CommentRequest.builder()
                    .oldMethod(data.getOldMethod())
                    .oldComment(data.getOldComment())
                    .newMethod(data.getNewMethod())
                    .modelName(options.getModelName())
                    .build();

            CompletableFuture<CommentResponse> future = client.generateComment(req);
            CommentResponse resp = future.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);

            if (resp != null && resp.isSuccess()) {
                log.debug("注释生成成功: {}", resp.getGeneratedComment());
                return resp.getGeneratedComment();
            }

            log.warn("注释生成失败");
            return null;
        } catch (Exception e) {
            log.error("注释生成服务异常", e);
            return null;
        }
    }

    public static List<String> getAvailableModels() {
        if (client == null) {
            log.info("CommentGeneratorClient 未初始化，正在初始化默认配置");
            init(null);
        }
        try {
            log.info("获取可用模型列表");
            CompletableFuture<List<String>> future = client.getAvailableModels();
            List<String> models = future.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);

            if (models == null || models.isEmpty()) {
                log.warn("未获取到可用模型列表");
                return List.of();
            }

            log.info("可用模型列表: {}", models);
            modelsList = models;
            return models;
        } catch (Exception e) {
            log.error("获取可用模型列表失败", e);
            return List.of();
        }
    }

    public static void setSelectedModel(String selectedModel) {
        log.info("设置选定模型: {}", selectedModel);
        CommentGeneratorClient.selectedModel = selectedModel;
    }

    public static void shutdown() {
        synchronized (LOCK) {
            if (client != null) {
                log.info("关闭 CommentGeneratorClient");
                client.shutdown();
                client = null;
            }
        }
    }
}
