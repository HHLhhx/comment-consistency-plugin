package com.nju.comment.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.nju.comment.dto.request.CommentRequest;
import com.nju.comment.dto.response.CommentResponse;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.*;

@Slf4j
public class PluginCommentClient implements CommentClient {

    private final String baseUrl;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final ExecutorService executor;
    private final Semaphore concurrentLimiter;
    private final Duration requestTimeout;

    private PluginCommentClient(Builder builder) {
        this.baseUrl = Objects.requireNonNull(builder.baseUrl, "url required");
        this.executor = Executors.newFixedThreadPool(Math.max(5, builder.threadPoolSize),
                r -> {
                    Thread t = new Thread(r);
                    t.setName("plugin-comment-client-%d".formatted(ThreadLocalRandom.current().nextInt(10000)));
                    t.setDaemon(true);
                    return t;
                });
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(builder.connectTimeout)
                .executor(executor)
                .build();
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        this.concurrentLimiter = new Semaphore(builder.maxConcurrentRequests);
        this.requestTimeout = builder.requestTimeout;
    }

    private <T> CompletableFuture<T> sendJson(String path, String method, String jsonBody, FunctionWithIOException<JsonNode, T> mapperFn) {
        boolean acquired;
        try {
            acquired = concurrentLimiter.tryAcquire(requestTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            log.error("请求被中断: {}", path, e);
            CompletableFuture<T> f = new CompletableFuture<>();
            f.completeExceptionally(e);
            return f;
        }
        if (!acquired) {
            log.info("请求并发数达到上限，拒绝请求: {}", path);
            CompletableFuture<T> f = new CompletableFuture<>();
            f.completeExceptionally(new TimeoutException("Timeout acquiring semaphore for request"));
            return f;
        }

        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(requestTimeout)
                .header("Content-Type", "application/json");

        if ("POST".equalsIgnoreCase(method)) {
            reqBuilder.POST(HttpRequest.BodyPublishers.ofString(jsonBody == null ? "" : jsonBody));
        } else {
            reqBuilder.GET();
        }

        HttpRequest request = reqBuilder.build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .orTimeout(requestTimeout.toMillis(), TimeUnit.MILLISECONDS)
                .thenApplyAsync(response -> {
                    int statusCode = response.statusCode();
                    String body = response.body();
                    log.info("statusCode: {}, body: \n{}", statusCode, body);
                    try {
                        JsonNode root = objectMapper.readTree(body);
                        return mapperFn.apply(root);
                    } catch (Exception e) {
                        log.error("response处理失败", e);
                        throw new CompletionException(e);
                    }
                }, executor)
                .whenComplete((res, ex) -> concurrentLimiter.release());
    }

    @Override
    public CompletableFuture<CommentResponse> generateComment(CommentRequest request) {
        try {
            String json = objectMapper.writeValueAsString(request);
            log.info("注释生成请求: \n{}", json);

            return sendJson("/comments/generate", "POST", json, root -> {
                boolean success = root.path("success").asBoolean(false);
                if (!success) {
                    log.warn("注释生成请求失败");
                    String msg = root.path("message").asText("Unknown error");
                    throw new CompletionException(new RuntimeException(msg));
                }
                log.info("注释生成请求成功");
                JsonNode dataNode = root.path("data");
                return objectMapper.treeToValue(dataNode, CommentResponse.class);
            });
        } catch (IOException e) {
            log.error("注释生成请求序列化失败", e);
            CompletableFuture<CommentResponse> f = new CompletableFuture<>();
            f.completeExceptionally(e);
            return f;
        }
    }

    @Override
    public CompletableFuture<List<CommentResponse>> batchGenerateComments(CommentRequest request) {
        return null;
    }

    @Override
    public CompletableFuture<Boolean> health() {
        return sendJson("/comments/health", "GET", null, root -> {
            boolean success = root.path("success").asBoolean(false);
            if (!success) {
                return Boolean.FALSE;
            }
            JsonNode dataNode = root.path("data");
            return dataNode.isBoolean() ? dataNode.asBoolean() : Boolean.FALSE;
        });
    }

    @Override
    public void shutdown() {
        log.info("关闭插件注释客户端线程池...");
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                log.info("线程池未能在规定时间内关闭，强制关闭中...");
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            log.warn("中断异常等待线程池关闭", e);
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String baseUrl;
        private int threadPoolSize = 10;
        private int maxConcurrentRequests = 20;
        private Duration connectTimeout = Duration.ofSeconds(10);
        private Duration requestTimeout = Duration.ofSeconds(20);

        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        public Builder threadPoolSize(int size) {
            this.threadPoolSize = size;
            return this;
        }

        public Builder maxConcurrentRequests(int maxRequests) {
            this.maxConcurrentRequests = maxRequests;
            return this;
        }

        public Builder connectTimeout(Duration timeout) {
            this.connectTimeout = timeout;
            return this;
        }

        public Builder requestTimeout(Duration timeout) {
            this.requestTimeout = timeout;
            return this;
        }

        public PluginCommentClient build() {
            return new PluginCommentClient(this);
        }
    }

    @FunctionalInterface
    private interface FunctionWithIOException<T, R> {
        R apply(T t) throws Exception;
    }
}
