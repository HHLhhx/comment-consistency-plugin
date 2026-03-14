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
import com.nju.comment.util.RequestIdGenerator;
import com.nju.comment.util.TextProcessUtil;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * 全局注释生成客户端（静态单例）。包装 {@link PluginCommentClient}，对外提供业务级 API。
 */
@Slf4j
public class CommentGeneratorClient {

    private static volatile CommentClient client;
    private static final Object LOCK = new Object();

    private static final Duration CLIENT_TIMEOUT = Duration.ofSeconds(Constant.CLIENT_REQUEST_TIMEOUT_S);
    private static final Duration LLM_TIMEOUT = Duration.ofSeconds(Constant.LLM_RESPONSE_TIMEOUT_S);

    // 方法维度的在途请求记录，用内容指纹区分「重复触发」与「修改后再触发」
    // key 格式: projectLocationHash + ":" + methodKey，保证跨项目不冲突
    private static final Map<String, InFlightRecord> IN_FLIGHT_BY_METHOD = new ConcurrentHashMap<>();
    private static final String FINGERPRINT_DELIM = "\u0001";

    /**
     * 可用模型列表
     */
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
     * 应用级 API Key 缓存
     */
    @Getter
    private static volatile String cachedApiKey = null;

    /**
     * API Key 是否已从后端加载过
     */
    @Getter
    private static volatile boolean apiKeyLoaded = false;

    // ========================== 客户端生命周期 ==========================

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
     * 关闭客户端，释放资源
     */
    public static void shutdown() {
        synchronized (LOCK) {
            // 取消所有在途请求
            IN_FLIGHT_BY_METHOD.keySet().forEach(CommentGeneratorClient::cancelByMapKey);
            IN_FLIGHT_BY_METHOD.clear();
            if (client != null) {
                log.info("关闭 CommentGeneratorClient");
                client.shutdown();
                client = null;
            }
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

    // ========================== 注释业务 ==========================

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

        // 将 methodKey 作用域化，避免跨项目同名方法冲突
        String mapKey = scopedKey(methodKey, project);

        // 计算内容指纹
        String fingerprint = contentFingerprint(data);

        // 原子去重 & 占位注册：compute() 对同一 key 加锁，杜绝 check-then-act 竞态
        if (mapKey != null && !mapKey.isBlank()) {
            InFlightRecord[] oldRecord = {null};
            boolean[] skipped = {false};

            IN_FLIGHT_BY_METHOD.compute(mapKey, (key, existing) -> {
                if (existing != null && Objects.equals(existing.getContentFingerprint(), fingerprint)) {
                    skipped[0] = true;
                    return existing; // 内容相同，保留在途，跳过本次
                }
                if (existing != null) {
                    oldRecord[0] = existing; // 内容变更，暂存旧记录待取消
                }
                // 注册占位（requestId/future 在池线程中填充）
                return new InFlightRecord(null, null, fingerprint);
            });

            if (skipped[0]) {
                log.info("方法 {} 已有相同内容的在途请求，跳过本次", methodKey);
                callback.accept(null);
                return;
            }

            // 在 compute 外取消旧记录（此时 map 中已是新占位，不会误取消）
            if (oldRecord[0] != null) {
                log.info("方法 {} 请求内容已变更，取消在途并发送新请求", methodKey);
                cancelRecord(oldRecord[0], mapKey);
            }
        }

        // 构建请求并发送（在后台线程中处理）
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                String requestId = RequestIdGenerator.generate();
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

                // 原子升级：占位 → 真实记录（仅当占位仍属于本次调用时才升级）
                if (mapKey != null && !mapKey.isBlank()) {
                    InFlightRecord upgraded = IN_FLIGHT_BY_METHOD.computeIfPresent(mapKey,
                            (key, current) -> {
                                if (current.getRequestId() == null
                                        && Objects.equals(current.getContentFingerprint(), fingerprint)) {
                                    return new InFlightRecord(requestId, future, fingerprint);
                                }
                                return current; // 已被更新的调用取代，保留新占位
                            });

                    if (upgraded == null || !Objects.equals(upgraded.getRequestId(), requestId)) {
                        // 占位已被取代或移除，取消本次请求
                        log.info("方法 {} 的占位已被取代，放弃本次请求, requestId={}", mapKey, requestId);
                        future.cancel(true);
                        if (client != null) client.cancelRequest(requestId);
                        callback.accept(null);
                        return;
                    }

                    // 完成后原子清理（仅移除自己的记录，不影响后续调用的记录）
                    future.whenComplete((r, ex) -> IN_FLIGHT_BY_METHOD.computeIfPresent(mapKey,
                            (key, cur) -> Objects.equals(cur.getRequestId(), requestId) ? null : cur));
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
                            ErrorHandler.handle(be, retryAction, project, methodKey);
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
     * 取消指定方法的在途请求（项目作用域）
     */
    public static void cancelForMethod(String methodKey, Project project) {
        String mapKey = scopedKey(methodKey, project);
        if (mapKey == null || mapKey.isBlank()) return;
        InFlightRecord record = IN_FLIGHT_BY_METHOD.remove(mapKey);
        cancelRecord(record, mapKey);
    }

    /**
     * 按 map 中的原始 key 取消（用于 shutdown 遍历）
     */
    private static void cancelByMapKey(String mapKey) {
        if (mapKey == null || mapKey.isBlank()) return;
        InFlightRecord record = IN_FLIGHT_BY_METHOD.remove(mapKey);
        cancelRecord(record, mapKey);
    }

    /**
     * 取消指定方法上正在进行的生成请求（并通知后端取消）
     *
     * @param methodKey 方法唯一键
     */
    private static void cancelRecord(InFlightRecord record, String methodKey) {
        if (record == null) return;
        if (client != null && record.getRequestId() != null) {
            client.cancelRequest(record.getRequestId());
        }
        if (record.getFuture() != null) {
            record.getFuture().cancel(true);
        }
        log.info("已取消方法 {} 的在途注释生成请求, requestId={}", methodKey, record.getRequestId());
    }

    /**
     * 生成项目作用域的 map key，避免跨项目同名方法在全局 map 中冲突。
     * 格式: {@code projectLocationHash:methodKey}
     */
    private static String scopedKey(String methodKey, Project project) {
        if (methodKey == null || methodKey.isBlank()) return null;
        String prefix = (project != null && !project.isDisposed())
                ? project.getLocationHash()
                : "_";
        return prefix + ":" + methodKey;
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

    // ========================== 认证 ==========================

    /**
     * 登录
     */
    public static CompletableFuture<AuthResponse> login(String username, String password) {
        initCheck();
        return client.login(new LoginRequest(username, password)).whenComplete((r, ex) -> {
            if (ex == null) {
                AuthManager.saveAuth(r.getToken(), username);
                cachedApiKey = null;
                apiKeyLoaded = false;
            }
        });
    }

    /**
     * 注册
     */
    public static CompletableFuture<AuthResponse> register(String username, String password, String phone) {
        initCheck();
        return client.register(new RegisterRequest(username, password, phone)).whenComplete((r, ex) -> {
            if (ex == null) {
                AuthManager.saveAuth(r.getToken(), username);
                cachedApiKey = null;
                apiKeyLoaded = false;
            }
        });
    }

    /**
     * 登出
     */
    public static void logout() {
        client.logout().whenComplete((v, ex) -> {
            if (ex != null) {
                log.warn("登出请求失败", ex);
            }
        });
        AuthManager.clearAuth();
        cachedApiKey = null;
        apiKeyLoaded = false;
        log.info("已登出");
    }

    // ========================== 用户设置 ==========================

    /**
     * 保存 API Key
     */
    public static CompletableFuture<Void> saveApiKey(String apiKey, Project project) {
        initCheck();
        return client.saveApiKey(new ApiKeyRequest(apiKey)).whenComplete((r, ex) -> {
            if (ex == null) {
                cachedApiKey = apiKey;
                apiKeyLoaded = true;
            } else {
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
     * 查询 API Key（返回完整 key，未设置时返回 null）
     */
    public static CompletableFuture<String> checkApiKey(Project project) {
        initCheck();
        return client.checkApiKey().whenComplete((r, ex) -> {
            if (ex == null) {
                cachedApiKey = r;
                apiKeyLoaded = true;
            } else {
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
            if (ex == null) {
                cachedApiKey = null;
                apiKeyLoaded = true;
            } else {
                Throwable cause = ex.getCause();
                if (cause instanceof BackendException be) {
                    ErrorHandler.handle(be, project);
                } else {
                    log.error("删除 API Key 请求失败", ex);
                }
            }
        });
    }
}
