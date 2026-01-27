package com.nju.comment.client.global;

import com.intellij.openapi.application.ApplicationManager;
import com.nju.comment.constant.Constant;
import com.nju.comment.dto.GenerateOptions;
import com.nju.comment.dto.InFlightRecord;
import com.nju.comment.dto.MethodContext;
import com.nju.comment.dto.request.CommentRequest;
import com.nju.comment.dto.response.CommentResponse;
import com.nju.comment.client.CommentClient;
import com.nju.comment.client.PluginCommentClient;
import com.nju.comment.util.TextProcessUtil;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.Consumer;

@Slf4j
public class CommentGeneratorClient {

    private static final String FINGERPRINT_DELIM = "\u0001";

    private static volatile CommentClient client;
    private static final Object LOCK = new Object();
    private static final Duration TIMEOUT = Duration.ofSeconds(Constant.CLIENT_REQUEST_TIMEOUT_S);

    // 方法维度的在途请求记录，用内容指纹区分「重复触发」与「修改后再触发」
    private static final Map<String, InFlightRecord> IN_FLIGHT_BY_METHOD = new ConcurrentHashMap<>();

    @Getter
    private static List<String> modelsList;

    @Getter
    private static volatile String selectedModel = null;

    /**
     * 用于判断同一方法下是「重复触发」还是「修改后再触发」。重复触发以最初为准；修改后再触发以最近为准。
     */
    private static String contentFingerprint(MethodContext ctx) {
        if (ctx == null) return "";
        String o = TextProcessUtil.safeTrimNullable(ctx.getOldMethod());
        String c = TextProcessUtil.safeTrimNullable(ctx.getOldComment());
        String n = TextProcessUtil.safeTrimNullable(ctx.getNewMethod());
        return o + FINGERPRINT_DELIM + c + FINGERPRINT_DELIM + n;
    }

    /**
     * 按方法维度的生成注释。异步模式，不阻塞调用线程。
     * 同一方法：重复触发（内容未变）以最初为准并跳过本次；在返回前又修改并再触发则以最近为准，会先取消在途请求再发新请求。
     *
     * @param methodKey 方法唯一键，为 null 时不按方法做在途去重/取消
     * @param data      方法上下文，用于计算内容指纹
     * @param options   生成选项
     * @param callback  异步回调，接收生成的注释文本（取消/跳过/失败时为null）
     */
    public static void generateCommentAsync(String methodKey, MethodContext data, GenerateOptions options,
                                            Consumer<String> callback) {
        // 初始化检查
        initCheck();

        // 计算内容指纹
        String fingerprint = contentFingerprint(data);
        if (methodKey != null && !methodKey.isBlank()) {
            InFlightRecord existing = IN_FLIGHT_BY_METHOD.get(methodKey);
            if (existing != null) {
                if (Objects.equals(existing.getContentFingerprint(), fingerprint)) {
                    log.info("方法 {} 已有相同内容的在途请求，跳过本次", methodKey);
                    callback.accept(null);
                    return;
                }
                log.info("方法 {} 请求内容已变更，取消在途并发送新请求", methodKey);
                cancelForMethod(methodKey);
            }
        }

        // 构建请求并发送（在后台线程中处理）
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
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
                    IN_FLIGHT_BY_METHOD.put(methodKey, new InFlightRecord(requestId, future, fingerprint));
                    future.whenComplete((r, ex) -> IN_FLIGHT_BY_METHOD.remove(methodKey));
                }

                // 在后台线程上等待结果，不阻塞UI线程
                future.whenComplete((resp, ex) -> {
                    try {
                        if (ex != null) {
                            // 解析真实异常
                            Throwable t = ex instanceof CompletionException && ex.getCause() != null
                                    ? ex.getCause()
                                    : ex;
                            if (t instanceof CancellationException) {
                                log.info("注释生成被取消, methodKey={}", methodKey);
                            } else {
                                log.error("注释生成服务异常", ex);
                            }
                            callback.accept(null);
                            return;
                        }

                        if (resp != null && resp.isSuccess()) {
                            log.debug("注释生成成功: {}", resp.getGeneratedComment());
                            callback.accept(resp.getGeneratedComment());
                        } else {
                            log.warn("注释生成失败");
                            callback.accept(null);
                        }
                    } catch (Exception e) {
                        log.error("处理注释生成结果异常", e);
                        callback.accept(null);
                    }
                });
            } catch (Exception e) {
                log.error("生成注释异常", e);
                callback.accept(null);
            }
        });
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
     * 取消指定方法上正在进行的生成请求（并通知后端取消）
     */
    public static void cancelForMethod(String methodKey) {
        if (methodKey == null || methodKey.isBlank()) return;
        InFlightRecord record = IN_FLIGHT_BY_METHOD.remove(methodKey);
        if (record == null) return;
        if (client != null) {
            client.cancelRequest(record.getRequestId());
        }
        record.getFuture().cancel(true);
        log.info("已取消方法 {} 的在途注释生成请求, requestId={}", methodKey, record.getRequestId());
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
