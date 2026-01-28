package com.nju.comment.constant;

public final class Constant {

    private  Constant() {
    }

    // UI refresh delays
    public static final int UI_REFRESH_INITIAL_DELAY_MS = 0;
    public static final int UI_REFRESH_DELAY_MS = 100;

    // Auto delete delays
    public static final int AUTO_DELETE_INITIAL_DELAY_MS = 3000;
    public static final int AUTO_DELETE_DELAY_MS = 3000;

    // Auto update delays
    public static final int AUTO_UPDATE_INITIAL_DELAY_MS = 3000;
    public static final int AUTO_UPDATE_DELAY_MS = 1000;

    // HTTP settings
    public static final int HTTP_DEFAULT_THREAD_POOL_SIZE = 10;
    public static final int HTTP_DEFAULT_MAX_CONNECTION_REQUESTS = 20;
    public static final int HTTP_DEFAULT_CONNECTION_TIMEOUT_S = 10;
    public static final int HTTP_DEFAULT_REQUEST_TIMEOUT_S = 20;

    // Client settings
    public static final String CLIENT_DEFAULT_BASE_URL = "http://localhost:8080/api";
    public static final int CLIENT_REQUEST_TIMEOUT_S = 20;
    public static final int CLIENT_THREAD_POOL_SIZE = 10;
    public static final int CLIENT_MAX_CONNECTION_REQUESTS = 20;
}
