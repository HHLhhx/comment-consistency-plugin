package com.nju.comment.client.global;

import com.nju.comment.dto.request.CommentRequest;
import com.nju.comment.dto.response.CommentResponse;
import com.nju.comment.client.PluginCommentClient;
import com.nju.comment.dto.MethodData;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Slf4j
public class CommentGeneratorClient {

    private static volatile PluginCommentClient client;
    private static final Object LOCK = new Object();
    private static final Duration TIMEOUT = Duration.ofSeconds(20);

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
            PluginCommentClient.Builder b = PluginCommentClient.builder();
            if (baseUrl != null && !baseUrl.isEmpty()) {
                b.baseUrl(baseUrl);
            } else {
                b.baseUrl("http://localhost:8080/api");
            }
            b.requestTimeout(TIMEOUT)
                    .threadPoolSize(10)
                    .maxConcurrentRequests(20);
            client = b.build();
            log.info("CommentGeneratorClient 初始化成功");
        }
    }

    public static String generateComment(MethodData data, Map<String, String> options) {
        if (client == null) {
            log.info("CommentGeneratorClient 未初始化，正在初始化默认配置");
            init(null);
        }
        try {
            log.info("开始生成注释");
            CommentRequest req = CommentRequest.builder()
                    .code((data.getSignature() == null ? "" : data.getSignature())
                            + "\n" + (data.getBody() == null ? "" : data.getBody()))
                    .existingComment(data.getExistingComment())
                    .language("java")
                    .options(CommentRequest.GenerationOptions.builder()
                            .style(options.getOrDefault("style", "Javadoc"))
                            .language(options.getOrDefault("language", "Chinese"))
                            .build())
                    .build();

            CompletableFuture<CommentResponse> future = client.generateComment(req);
            CommentResponse resp = future.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            if (resp != null && resp.isSuccess()) {
                log.info("注释生成成功: {}", resp.getGeneratedComment());
                return resp.getGeneratedComment();
            } else {
                log.warn("注释生成失败");
                return null;
            }
        } catch (Exception e) {
            log.error("注释生成服务异常", e);
            return null;
        }
    }

    public static CompletableFuture<Boolean> health() {
        if (client == null) {
            init(null);
        }
        try {
            log.info("开始健康检查");
            return client.health();
        } catch (Exception e) {
            log.error("健康检查异常", e);
            CompletableFuture<Boolean> f = new CompletableFuture<>();
            f.completeExceptionally(e);
            return f;
        }
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
