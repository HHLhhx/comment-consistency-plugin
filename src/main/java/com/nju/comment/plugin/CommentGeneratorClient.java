package com.nju.comment.plugin;

import java.io.InputStream;

public class CommentGeneratorClient {

    private static final String BACKEND_URL = "http://localhost:8000/comment";

    public static String generateComment(String methodText, String option) {
        return option;
    }

    private static String readStream(InputStream io) {
        return "todo";
    }

    private static String escapeJson(String s) {
        return "todo";
    }
}
