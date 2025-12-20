package com.nju.comment.client.global;

import com.nju.comment.backend.dto.request.CommentRequest;
import com.nju.comment.backend.dto.response.CommentResponse;
import com.nju.comment.client.PluginCommentClient;
import com.nju.comment.plugin.MethodData;
import com.intellij.openapi.diagnostic.Logger;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class CommentGeneratorClient {

    private static volatile PluginCommentClient client;
    private static final Object lock = new Object();
    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    private static final Logger LOG = Logger.getInstance(CommentGeneratorClient.class);

    public static void init(String baseUrl) {
        if (client != null) {
            LOG.info("CommentGeneratorClient already initialized.");
            return;
        }
        synchronized (lock) {
            if (client != null) return;
            LOG.info("CommentGeneratorClient try to init");
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
            LOG.info("CommentGeneratorClient initialized");
        }
    }

    public static String generateComment(MethodData data, Map<String, String> options) {
        if (client == null) {
            init(null);
        }
        try {
            LOG.info("CommentGeneratorClient try to generate comment");
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
                LOG.info("Generated comment: \n" + resp.getGeneratedComment());
                return resp.getGeneratedComment();
            } else {
                LOG.warn("Failed to generate comment");
                return null;
            }
        } catch (Exception e) {
            LOG.warn("Failed to generate comment", e);
            return null;
        }
    }

    public static CompletableFuture<Boolean> health() {
        if (client == null) {
            init(null);
        }
        try {
            LOG.info("CommentGeneratorClient try to health");
            return client.health();
        } catch (Exception e) {
            LOG.warn("Health check failed", e);
            CompletableFuture<Boolean> f = new CompletableFuture<>();
            f.completeExceptionally(e);
            return f;
        }
    }

    public static void shutdown() {
        synchronized (lock) {
            if (client != null) {
                LOG.info("Shutting down CommentGeneratorClient");
                client.shutdown();
                client = null;
            }
        }
    }
}
