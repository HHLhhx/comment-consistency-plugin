package com.nju.comment.client.global;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.nju.comment.constant.Constant;
import com.nju.comment.pojo.GenerateOptions;
import com.nju.comment.pojo.InFlightRecord;
import com.nju.comment.pojo.MethodContext;
import com.nju.comment.dto.request.ApiKeyRequest;
import com.nju.comment.dto.request.CommentRequest;
import com.nju.comment.dto.request.LoginRequest;
import com.nju.comment.dto.request.RegisterRequest;
import com.nju.comment.dto.response.AuthResponse;
import com.nju.comment.dto.response.CommentResponse;
import com.nju.comment.client.CommentClient;
import com.nju.comment.client.PluginCommentClient;
import com.nju.comment.exception.BackendException;
import com.nju.comment.exception.ErrorHandler;
import com.nju.comment.service.AuthManager;
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

/**
 * 全局注释生成客户端（静态单例）。包装 {@link PluginCommentClient}，对外提供业务级 API。
 * <p>
 * <b>异步策略说明</b>：
 * <ul>
 *   <li><b>generateCommentAsync</b> — 纯异步回调。LLM 推理耗时长（秒级），阻塞任何线程都不合理。</li>
 *   <li><b>login / register / saveApiKey / checkApiKey / deleteApiKey</b> — 返回 CompletableFuture，
 *       由 UI 层通过 {@code thenAccept/exceptionally} 链式消费，避免阻塞 EDT。</li>
 *   <li><b>getAvailableModels</b> — 同步阻塞（内部 {@code future.get()}）。
 *       调用方始终在后台线程池中运行（{@code executeOnPooledThread}），
 *       且返回值需直接写入 {@code modelsList} 静态状态，链式回调反而增加复杂度。</li>
 *   <li><b>logout</b> — 发后即忘（fire-and-forget）。无需等待后端确认，本地立即清除凭证。</li>
 *   <li><b>cancelForMethod</b> — 同步触发取消，忽略返回值。取消是尽力而为语义，不需要等待确认。</li>
 * </ul>
 */
@Slf4j
public class CommentGeneratorClient {

    private static volatile CommentClient client;
    private static final Object LOCK = new Object();

    private static final Duration CLIENT_TIMEOUT = Duration.ofSeconds(Constant.CLIENT_REQUEST_TIMEOUT_S);
    private static final Duration LLM_TIMEOUT = Duration.ofSeconds(Constant.LLM_RESPONSE_TIMEOUT_S);

    // 方法维度的在途请求记录，用内容指纹区分「重复触发」与「修改后再触发」
    private static final Map<String, InFlightRecord> IN_FLIGHT_BY_METHOD = new ConcurrentHashMap<>();
    private static final String FINGERPRINT_DELIM = "\u0001";

    @Getter
    private static volatile List<String> modelsList;

    /**
     * 用户选定的模型
     */
    @Getter
    private static volatile String selectedModel = null;

    /**
     * 用户是否启用 RAG 服务
     */
    @Getter
    private static volatile boolean ragEnabled = false;

    /**
     * 初始化客户端
     *
     * @param baseUrl 服务端基础URL，null或空字符串时使用默认值
     */
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
            clientBuilder.requestTimeout(CLIENT_TIMEOUT)
                    .threadPoolSize(Constant.CLIENT_THREAD_POOL_SIZE)
                    .maxConcurrentRequests(Constant.CLIENT_MAX_CONNECTION_REQUESTS);
            client = clientBuilder.build();
            log.info("CommentGeneratorClient 初始化成功");
        }
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
                                            Consumer<String> callback, Project project) {
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
                        .requestId(requestId)
                        .timeoutMs(LLM_TIMEOUT.toMillis())
                        .tag(options.getTag())
                        .build();

                CompletableFuture<CommentResponse> future = client.generateComment(req);

                // 记录在途请求
                if (methodKey != null && !methodKey.isBlank()) {
                    IN_FLIGHT_BY_METHOD.put(methodKey, new InFlightRecord(requestId, future, fingerprint));
                    future.whenComplete((r, ex) -> IN_FLIGHT_BY_METHOD.remove(methodKey));
                }

                // 在后台线程上等待结果，不阻塞UI线程
                future.whenComplete((resp, ex) -> {
                    if (ex != null) {
                        // 解析真实异常
                        Throwable t = ex instanceof CompletionException && ex.getCause() != null
                                ? ex.getCause()
                                : ex;
                        if (t instanceof CancellationException) {
                            log.info("注释生成被取消, requestId={}, methodKey={}", requestId, methodKey);
                        } else if (t instanceof BackendException be) {
                            // 委托 ErrorHandler 统一处理，可重试的错误码传入重试动作
                            Runnable retryAction = be.getErrorCode().isRetryable()
                                    ? () -> generateCommentAsync(methodKey, data, options, callback, project)
                                    : null;
                            ErrorHandler.handle(be, retryAction, project);
                        } else {
                            log.error("注释生成异常, requestId={}", requestId, t);
                        }
                        callback.accept(null);
                        return;
                    }

                    if (resp != null && resp.isSuccess()) {
                        // 成功
                        log.info("注释生成成功:\n{}", resp.getGeneratedComment());
                        callback.accept(resp.getGeneratedComment());
                    } else {
                        // 失败
                        log.warn("注释生成失败");
                        callback.accept(null);
                    }
                });
            } catch (Exception e) {
                log.error("生成注释异常", e);
                callback.accept(null);
            }
        });
    }

    /**
     * 用于判断同一方法下是「重复触发」还是「修改后再触发」。重复触发以最初为准；修改后再触发以最近为准。
     *
     * @param ctx 方法上下文
     * @return 内容指纹字符串
     */
    private static String contentFingerprint(MethodContext ctx) {
        if (ctx == null) return "";
        String o = TextProcessUtil.safeTrimNullable(ctx.getOldMethod());
        String c = TextProcessUtil.safeTrimNullable(ctx.getOldComment());
        String n = TextProcessUtil.safeTrimNullable(ctx.getNewMethod());
        return o + FINGERPRINT_DELIM + c + FINGERPRINT_DELIM + n;
    }

    /**
     * 取消指定方法上正在进行的生成请求（并通知后端取消）
     *
     * @param methodKey 方法唯一键
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

    /**
     * 获取可用模型列表
     *
     * @return 模型名称列表，失败时返回空列表
     */
    public static List<String> getAvailableModels(Project project) {
        initCheck();
        try {
            log.info("获取可用模型列表");
            CompletableFuture<List<String>> future = client.getAvailableModels();
            List<String> models = future.get();

            if (models == null || models.isEmpty()) {
                log.warn("未获取到可用模型列表");
                return List.of();
            }

            models.sort(String::compareTo);
            log.info("可用模型列表: {}", models);
            modelsList = models;
            return models;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof BackendException be) {
                ErrorHandler.handle(be, project);
            } else {
                log.error("获取可用模型列表失败", e);
            }
            return List.of();
        } catch (Exception e) {
            log.error("获取可用模型列表失败", e);
            return List.of();
        }
    }

    /**
     * 初始化检查
     */
    private static void initCheck() {
        if (client == null) {
            log.info("CommentGeneratorClient 未初始化，正在初始化默认配置");
            init(null);
        }
    }

    /**
     * 设置选定模型
     *
     * @param selectedModel 选定模型名称
     */
    public static void setSelectedModel(String selectedModel) {
        log.info("设置选定模型: {}", selectedModel);
        CommentGeneratorClient.selectedModel = selectedModel;
    }

    /**
     * 设置 RAG 服务启用状态
     *
     * @param enabled true 启用 RAG 服务，false 关闭 RAG 服务
     */
    public static void setRagEnabled(boolean enabled) {
        log.info("RAG 服务: {}", enabled ? "启用" : "关闭");
        CommentGeneratorClient.ragEnabled = enabled;
    }

    /**
     * 登录
     */
    public static CompletableFuture<AuthResponse> login(String username, String password) {
        initCheck();
        return client.login(new LoginRequest(username, password));
    }

    /**
     * 注册
     */
    public static CompletableFuture<AuthResponse> register(String username, String password, String phone) {
        initCheck();
        return client.register(new RegisterRequest(username, password, phone));
    }

    /**
     * 登出
     */
    public static void logout() {
        if (client != null) {
            client.logout().whenComplete((v, ex) -> {
                if (ex != null) {
                    log.warn("登出请求失败", ex);
                }
            });
        }
        AuthManager.clearAuth();
        log.info("已登出");
    }

    /**
     * 保存 API Key
     */
    public static CompletableFuture<Void> saveApiKey(String apiKey, Project project) {
        initCheck();
        return client.saveApiKey(new ApiKeyRequest(apiKey)).whenComplete((r, ex) -> {
            if (ex != null) {
                Throwable cause = ex.getCause();
                if (cause instanceof BackendException be) {
                    ErrorHandler.handle(be, project);
                } else {
                    log.error("保存 API Key 请求失败", ex);
                }
            }
        });
    }

    /**
     * 查询 API Key（返回脱敏后的 Key，未设置时返回 null）
     */
    public static CompletableFuture<String> checkApiKey(Project project) {
        initCheck();
        return client.checkApiKey().whenComplete((r, ex) -> {
            if (ex != null) {
                Throwable cause = ex.getCause();
                if (cause instanceof BackendException be) {
                    ErrorHandler.handle(be, project);
                } else {
                    log.error("查询 API Key 请求失败", ex);
                }
            }
        });
    }

    /**
     * 删除 API Key
     */
    public static CompletableFuture<Void> deleteApiKey(Project project) {
        initCheck();
        return client.deleteApiKey().whenComplete((r, ex) -> {
            if (ex != null) {
                Throwable cause = ex.getCause();
                if (cause instanceof BackendException be) {
                    ErrorHandler.handle(be, project);
                } else {
                    log.error("删除 API Key 请求失败", ex);
                }
            }
        });
    }

    /**
     * 关闭客户端，释放资源
     */
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
