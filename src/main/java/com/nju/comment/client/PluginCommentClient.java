package com.nju.comment.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.nju.comment.constant.Constant;
import com.nju.comment.dto.request.ApiKeyRequest;
import com.nju.comment.dto.request.CommentRequest;
import com.nju.comment.dto.request.LoginRequest;
import com.nju.comment.dto.request.RegisterRequest;
import com.nju.comment.dto.response.ApiResponse;
import com.nju.comment.dto.response.AuthResponse;
import com.nju.comment.dto.response.CommentResponse;
import com.nju.comment.service.AuthManager;
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

    // ========================== 请求引擎 ==========================

    /** 发送无请求体的请求（GET / DELETE / 空 POST），默认携带认证头 */
    private <T> CompletableFuture<T> send(String path, String method, ResponseMapper<T> mapper) {
        return doSend(path, method, null, mapper, true);
    }

    /** 发送带请求体的请求（POST / PUT），自动序列化，默认携带认证头 */
    private <T> CompletableFuture<T> sendBody(String path, String method, Object body,
                                               ResponseMapper<T> mapper) {
        return sendBody(path, method, body, mapper, true);
    }

    /** 发送带请求体的请求，可控是否携带认证头 */
    private <T> CompletableFuture<T> sendBody(String path, String method, Object body,
                                               ResponseMapper<T> mapper, boolean authenticated) {
        try {
            String json = objectMapper.writeValueAsString(body);
            return doSend(path, method, json, mapper, authenticated);
        } catch (IOException e) {
            log.warn("请求序列化失败: path={}, method={}, body={}", path, method, body, e);
            return CompletableFuture.failedFuture(e);
        }
    }

    /** 统一请求骨架：信号量限流 → 构建 HTTP 请求 → 异步发送 → 响应映射 */
    private <T> CompletableFuture<T> doSend(String path, String method, String jsonBody,
                                             ResponseMapper<T> mapper, boolean authenticated) {
        boolean acquired;
        try {
            acquired = concurrentLimiter.tryAcquire(requestTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            return CompletableFuture.failedFuture(e);
        }
        if (!acquired) {
            return CompletableFuture.failedFuture(
                    new TimeoutException("请求并发数达到上限: " + path));
        }

        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(requestTimeout)
                .header("Content-Type", "application/json");

        if (authenticated) {
            String token = AuthManager.getToken();
            if (token != null && !token.isBlank()) {
                reqBuilder.header("Authorization", "Bearer " + token);
            }
        }

        switch (method.toUpperCase()) {
            case "POST"   -> reqBuilder.POST(bodyPublisher(jsonBody));
            case "PUT"    -> reqBuilder.PUT(bodyPublisher(jsonBody));
            case "DELETE" -> reqBuilder.DELETE();
            default       -> reqBuilder.GET();
        }

        return httpClient.sendAsync(reqBuilder.build(), HttpResponse.BodyHandlers.ofString())
                .orTimeout(requestTimeout.toMillis(), TimeUnit.MILLISECONDS)
                .thenApplyAsync(response -> {
                    log.info("[{}] {} → {}", method, path, response.statusCode());
                    try {
                        return mapper.apply(objectMapper.readTree(response.body()));
                    } catch (CompletionException ce) {
                        throw ce;
                    } catch (BackendException be) {
                        throw new CompletionException(be);
                    } catch (Exception e) {
                        throw new CompletionException(e);
                    }
                }, executor)
                .whenComplete((res, ex) -> concurrentLimiter.release());
    }

    private static HttpRequest.BodyPublisher bodyPublisher(String json) {
        return HttpRequest.BodyPublishers.ofString(json == null ? "" : json);
    }

    // ========================== 响应校验 ==========================

    private ApiResponse checkResponse(JsonNode root) {
        return checkResponse(root, null);
    }

    private ApiResponse checkResponse(JsonNode root, String requestId) {
        ApiResponse resp = parseApiResponse(root);
        if (!resp.isSuccess()) {
            throw newBackendException(resp, requestId);
        }
        return resp;
    }

    private ApiResponse parseApiResponse(JsonNode root) {
        ApiResponse resp = new ApiResponse();
        resp.setSuccess(root.path("success").asBoolean(false));
        resp.setCode(root.path("code").asInt(0));
        resp.setMessage(root.path("message").asText("Unknown error"));
        resp.setData(root.path("data"));
        resp.setServerTime(root.path("serverTime").asLong(0));
        return resp;
    }

    private BackendException newBackendException(ApiResponse apiResp, String requestId) {
        ErrorCode errorCode = ErrorCode.fromCode(apiResp.getCode());
        log.warn("后端返回错误 [code={}, errorCode={}, requestId={}]: {}",
                apiResp.getCode(), errorCode.name(), requestId, apiResp.getMessage());
        return new BackendException(errorCode, apiResp.getMessage());
    }

    // ========================== 注释业务 ==========================

    @Override
    public CompletableFuture<CommentResponse> generateComment(CommentRequest request) {
        return sendBody("/comments/generate", "POST", request, root -> {
            ApiResponse resp = checkResponse(root, request.getRequestId());
            return objectMapper.treeToValue(resp.getData(), CommentResponse.class);
        });
    }

    @Override
    public CompletableFuture<Void> cancelRequest(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            log.warn("取消请求失败，requestId 为空");
            return CompletableFuture.completedFuture(null);
        }
        return sendBody("/comments/cancel", "POST", new CancelPayload(requestId), root -> {
            ApiResponse resp = parseApiResponse(root);
            if (!resp.isSuccess()) {
                log.warn("取消请求返回失败, requestId={}, code={}, msg={}",
                        requestId, resp.getCode(), resp.getMessage());
            }
            return null;
        });
    }

    @Override
    public CompletableFuture<List<String>> getAvailableModels() {
        return send("/comments/models", "GET", root -> {
            ApiResponse resp = checkResponse(root);
            return objectMapper.convertValue(resp.getData(),
                    objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        });
    }

    // ========================== 认证 ==========================

    @Override
    public CompletableFuture<AuthResponse> login(LoginRequest request) {
        return sendBody("/auth/login", "POST", request, root -> {
            ApiResponse resp = checkResponse(root);
            return objectMapper.treeToValue(resp.getData(), AuthResponse.class);
        }, false);
    }

    @Override
    public CompletableFuture<AuthResponse> register(RegisterRequest request) {
        return sendBody("/auth/register", "POST", request, root -> {
            ApiResponse resp = checkResponse(root);
            return objectMapper.treeToValue(resp.getData(), AuthResponse.class);
        }, false);
    }

    @Override
    public CompletableFuture<Void> logout() {
        return send("/auth/logout", "POST", root -> {
            checkResponse(root);
            return null;
        });
    }

    // ========================== 用户设置 ==========================

    @Override
    public CompletableFuture<Void> saveApiKey(ApiKeyRequest request) {
        return sendBody("/settings/api-key", "PUT", request, root -> {
            checkResponse(root);
            return null;
        });
    }

    @Override
    public CompletableFuture<String> checkApiKey() {
        return send("/settings/api-key", "GET", root -> {
            ApiResponse resp = checkResponse(root);
            JsonNode data = resp.getData();
            return (data == null || data.isNull()) ? null : data.asText();
        });
    }

    @Override
    public CompletableFuture<Void> deleteApiKey() {
        return send("/settings/api-key", "DELETE", root -> {
            checkResponse(root);
            return null;
        });
    }

    // ========================== 基础设施 ==========================

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

    @FunctionalInterface
    private interface ResponseMapper<R> {
        R apply(JsonNode root) throws Exception;
    }

    private record CancelPayload(String requestId) {}
}
