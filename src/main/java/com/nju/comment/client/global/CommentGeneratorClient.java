package com.nju.comment.client.global;

import com.nju.comment.constant.Constant;
import com.nju.comment.dto.GenerateOptions;
import com.nju.comment.dto.MethodContext;
import com.nju.comment.dto.request.CommentRequest;
import com.nju.comment.dto.response.CommentResponse;
import com.nju.comment.client.CommentClient;
import com.nju.comment.client.PluginCommentClient;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;

@Slf4j
public class CommentGeneratorClient {

    private static volatile CommentClient client;
    private static final Object LOCK = new Object();
    private static final Duration TIMEOUT = Duration.ofSeconds(Constant.CLIENT_REQUEST_TIMEOUT_S);

    // 方法维度的在途请求记录
    private static final Map<String, InFlightRecord> IN_FLIGHT_BY_METHOD = new ConcurrentHashMap<>();

    @Getter
    private static List<String> modelsList;

    @Getter
    private static volatile String selectedModel = null;

    private record InFlightRecord(String requestId, CompletableFuture<CommentResponse> future) {
    }

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
                clientBuilder.baseUrl(Constant.CLIENT_DEFAULT_BASE_URL);
            }
            clientBuilder.requestTimeout(TIMEOUT)
                    .threadPoolSize(Constant.CLIENT_THREAD_POOL_SIZE)
                    .maxConcurrentRequests(Constant.CLIENT_MAX_CONNECTION_REQUESTS);
            client = clientBuilder.build();
            log.info("CommentGeneratorClient 初始化成功");
        }
    }

    /**
     * 按方法维度的生成注释（支持取消旧请求、跳过重复请求）。
     *
     * @param methodKey                方法唯一键，为 null 时不做在途去重/取消
     * @param data                     方法上下文
     * @param options                  生成选项
     * @param cancelPreviousIfInFlight true 时若该 method 已有在途请求则先取消再发新请求（覆盖）；false 时若有在途请求则直接返回 null（跳过）
     * @return 生成的注释文本，取消/跳过/失败时返回 null
     */
    public static String generateComment(String methodKey, MethodContext data, GenerateOptions options,
                                         boolean cancelPreviousIfInFlight) {
        initCheck();
        if (methodKey != null && !methodKey.isBlank()) {
            if (cancelPreviousIfInFlight) {
                cancelForMethod(methodKey);
            } else if (hasInFlightForMethod(methodKey)) {
                log.info("方法 {} 已有在途注释生成请求，跳过本次", methodKey);
                return null;
            }
        }
        try {
            String requestId = UUID.randomUUID().toString();
            log.info("开始生成注释, requestId={}, methodKey={}", requestId, methodKey);
            CommentRequest req = CommentRequest.builder()
                    .oldMethod(data.getOldMethod())
                    .oldComment(data.getOldComment())
                    .newMethod(data.getNewMethod())
                    .modelName(options.getModelName())
                    .clientRequestId(requestId)
                    .build();

            CompletableFuture<CommentResponse> future = client.generateComment(req);

            // 记录在途请求
            if (methodKey != null && !methodKey.isBlank()) {
                IN_FLIGHT_BY_METHOD.put(methodKey, new InFlightRecord(requestId, future));
                future.whenComplete((r, ex) -> IN_FLIGHT_BY_METHOD.remove(methodKey));
            }

            CommentResponse resp = future.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);

            if (resp != null && resp.isSuccess()) {
                log.debug("注释生成成功: {}", resp.getGeneratedComment());
                return resp.getGeneratedComment();
            }

            log.warn("注释生成失败");
            return null;
        } catch (CancellationException e) {
            log.info("注释生成被取消, methodKey={}", methodKey);
            return null;
        } catch (Exception e) {
            log.error("注释生成服务异常", e);
            return null;
        }
    }

    /**
     * 兼容旧调用：不按方法去重，不取消前序请求
     */
    public static String generateComment(MethodContext data, GenerateOptions options) {
        return generateComment(null, data, options, false);
    }

    /**
     * 取消指定方法上正在进行的生成请求（并通知后端取消）
     */
    public static void cancelForMethod(String methodKey) {
        if (methodKey == null || methodKey.isBlank()) return;
        InFlightRecord record = IN_FLIGHT_BY_METHOD.remove(methodKey);
        if (record == null) return;
        if (client != null) {
            client.cancelRequest(record.requestId);
        }
        record.future.cancel(true);
        log.info("已取消方法 {} 的在途注释生成请求, requestId={}", methodKey, record.requestId);
    }

    /**
     * 是否有指定方法的在途注释生成请求
     */
    public static boolean hasInFlightForMethod(String methodKey) {
        return methodKey != null && !methodKey.isBlank() && IN_FLIGHT_BY_METHOD.containsKey(methodKey);
    }

    public static List<String> getAvailableModels() {
        initCheck();
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

    private static void initCheck() {
        if (client == null) {
            log.info("CommentGeneratorClient 未初始化，正在初始化默认配置");
            init(null);
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
