package com.nju.comment.util;

public final class TextProcessUtil {

    private TextProcessUtil() {
    }

    public static String processComment(String comment) {
        comment = safeTrimNullable(comment);

        //TODO: 前置处理

        String[] lines = comment.split("\\n");

        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            line = line.trim();
            if (line.equals("*")) continue;
            if (line.startsWith("/**")) {
                sb.append(line).append("\n");
            } else {
                sb.append(" ").append(line).append("\n");
            }
        }

        return sb.toString().trim();
    }

    public static String processMethod(String method) {
        method = safeTrimNullable(method);
        String[] lines = method.split("\\n");

        int indent = 0;
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("}") || line.startsWith(")") || line.startsWith("]")) {
                indent--;
            }
            sb.append("\t".repeat(Math.max(0, indent)));
            sb.append(line).append("\n");
            if (line.endsWith("{") || line.endsWith("(") || line.endsWith("[")) {
                indent++;
            }
        }

        return sb.toString().trim();
    }

    public static String safeTrim(String s) {
        return s == null ? "" : s.trim();
    }

    public static String safeTrimNullable(String s) {
        return (s == null || s.trim().isEmpty()) ? "" : s.trim();
    }
}
