package com.nju.comment.plugin;

import java.io.InputStream;
import java.util.Map;

public class CommentGeneratorClient {

    private static final String BACKEND_URL = "http://localhost:8000/comment";

    public static String generateComment(MethodData methodData, Map<String, String> options) {
        return methodData.toString() + "\n" + options.toString();
    }

    private static String readStream(InputStream io) {
        return "todo";
    }

    private static String escapeJson(String s) {
        return "todo";
    }
}
