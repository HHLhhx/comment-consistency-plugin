package com.nju.comment.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.nju.comment.constant.Constant;
import com.nju.comment.dto.request.CommentRequest;
import com.nju.comment.dto.response.ApiResponse;
import com.nju.comment.dto.response.CommentResponse;
import com.nju.comment.exception.BackendException;
import com.nju.comment.exception.ErrorCode;
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

    private <T> CompletableFuture<T> sendJson(String path, String method, String jsonBody,
                                              FunctionWithIOException<JsonNode, T> mapperFn) {
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
                    } catch (CompletionException ce) {
                        // 已包装的异常（如 BackendException），直接传播
                        throw ce;
                    } catch (BackendException be) {
                        // 业务异常，包装后传播，不打 ERROR 日志
                        throw new CompletionException(be);
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
                ApiResponse apiResp = parseApiResponse(root);
                if (!apiResp.isSuccess()) {
                    throw newBackendException(apiResp, request.getRequestId());
                }
                log.info("注释生成请求成功");
                return objectMapper.treeToValue(apiResp.getData(), CommentResponse.class);
            });
        } catch (IOException e) {
            log.error("注释生成请求序列化失败", e);
            CompletableFuture<CommentResponse> f = new CompletableFuture<>();
            f.completeExceptionally(e);
            return f;
        }
    }

    @Override
    public void cancelRequest(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            log.warn("取消请求失败，requestId 为空");
            return;
        }

        try {
            String json = objectMapper.writeValueAsString(new CancelRequestPayload(requestId));
            log.info("发送取消请求: \n{}", json);

            sendJson("/comments/cancel", "POST", json, root -> {
                ApiResponse apiResp = parseApiResponse(root);
                if (!apiResp.isSuccess()) {
                    log.warn("取消请求失败, requestId={}, code={}, msg={}",
                            requestId, apiResp.getCode(), apiResp.getMessage());
                    return null;
                }
                log.info("取消请求成功, requestId={}", requestId);
                return null;
            });
        } catch (Exception e) {
            log.error("取消请求异常, requestId={}", requestId, e);
        }
    }

    @Override
    public CompletableFuture<List<String>> getAvailableModels() {
        try {
            return sendJson("/comments/models", "GET", null, root -> {
                ApiResponse apiResp = parseApiResponse(root);
                if (!apiResp.isSuccess()) {
                    throw newBackendException(apiResp, null);
                }
                log.info("获取可用模型请求成功");
                return objectMapper.convertValue(apiResp.getData(),
                        objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
            });
        } catch (Exception e) {
            log.error("获取可用模型请求失败", e);
            CompletableFuture<List<String>> f = new CompletableFuture<>();
            f.completeExceptionally(e);
            return f;
        }
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
        private int threadPoolSize = Constant.HTTP_DEFAULT_THREAD_POOL_SIZE;
        private int maxConcurrentRequests = Constant.HTTP_DEFAULT_MAX_CONNECTION_REQUESTS;
        private Duration connectTimeout = Duration.ofSeconds(Constant.HTTP_DEFAULT_CONNECTION_TIMEOUT_S);
        private Duration requestTimeout = Duration.ofSeconds(Constant.HTTP_DEFAULT_REQUEST_TIMEOUT_S);

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

    // ========================== 响应解析工具方法 ==========================

    /**
     * 将 JsonNode 解析为 ApiResponse，统一提取 success/code/message/data
     */
    private ApiResponse parseApiResponse(JsonNode root) {
        ApiResponse resp = new ApiResponse();
        resp.setSuccess(root.path("success").asBoolean(false));
        resp.setCode(root.path("code").asInt(0));
        resp.setMessage(root.path("message").asText("Unknown error"));
        resp.setData(root.path("data"));
        resp.setServerTime(root.path("serverTime").asLong(0));
        return resp;
    }

    /**
     * 根据 ApiResponse 构造 BackendException
     */
    private BackendException newBackendException(ApiResponse apiResp, String requestId) {
        ErrorCode errorCode = ErrorCode.fromCode(apiResp.getCode());
        log.warn("后端返回错误 [code={}, errorCode={}, requestId={}]: {}",
                apiResp.getCode(), errorCode.name(), requestId, apiResp.getMessage());
        return new BackendException(errorCode, apiResp.getMessage());
    }

    @FunctionalInterface
    private interface FunctionWithIOException<T, R> {
        R apply(T t) throws Exception;
    }

    private record CancelRequestPayload(@SuppressWarnings("unused") String requestId) {
        private CancelRequestPayload(String requestId) {
            this.requestId = requestId;
        }
    }
}
