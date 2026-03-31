package com.nju.comment.constant;

import java.io.InputStream;
import java.util.Properties;

public final class Constant {

    private  Constant() {
    }


    // UI refresh delays
    public static final int UI_REFRESH_INITIAL_DELAY_MS = 0;
    public static final int UI_REFRESH_DELAY_MS = 100;

    // Auto delete delays
    public static final int AUTO_DELETE_INITIAL_DELAY_MS = 3000;
    public static final int AUTO_DELETE_DELAY_MS = 3000;

    // Dirty files refresh debounce time
    public static final long DIRTY_REFRESH_DEBOUNCE_MS = 400L;

    // HTTP default settings
    public static final int HTTP_DEFAULT_THREAD_POOL_SIZE = 10;
    public static final int HTTP_DEFAULT_MAX_CONNECTION_REQUESTS = 20;
    public static final int HTTP_DEFAULT_CONNECTION_TIMEOUT_S = 60;
    public static final int HTTP_DEFAULT_REQUEST_TIMEOUT_S = 20;

    // Client settings
    public static final String CLIENT_DEFAULT_BASE_URL = resolveClientBaseUrl();
    public static final int CLIENT_REQUEST_TIMEOUT_S = 60;
    public static final int CLIENT_THREAD_POOL_SIZE = 10;
    public static final int CLIENT_MAX_CONNECTION_REQUESTS = 20;

    // LLM settings
    public static final int LLM_RESPONSE_TIMEOUT_S = 30;

    // Max concurrent refresh methods
    public static final int MAX_CONCURRENT_REFRESH = 8;

    // Gutter refresh debounce time
    public static final long GUTTER_REFRESH_DEBOUNCE_MS = 500;

    private static String resolveClientBaseUrl() {
        try (InputStream in = Constant.class.getClassLoader().getResourceAsStream("comment-consistency.properties")) {
            if (in != null) {
                Properties props = new Properties();
                props.load(in);
                String propUrl = props.getProperty("client.baseUrl");
                if (propUrl != null && !propUrl.isBlank()) {
                    return propUrl;
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("加载配置文件失败", e);
        }
        return "http://localhost:8080/api";
    }
}
