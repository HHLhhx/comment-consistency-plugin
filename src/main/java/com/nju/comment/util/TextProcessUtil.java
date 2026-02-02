package com.nju.comment.util;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class TextProcessUtil {

    private TextProcessUtil() {
    }

    public static String processComment(String comment) {
        comment = safeTrimNullable(comment);

        if (comment.isEmpty()) {
            log.warn("注释文本为空");
            return comment;
        }

        int startIndex = comment.indexOf("/**");
        if (startIndex < 0) {
            log.warn("注释文本不符合规范，缺少 /** 开头");
            return null;
        }
        comment = comment.substring(startIndex);

        int endIndex = comment.lastIndexOf("*/");
        if (endIndex < 0) {
            log.warn("注释文本不符合规范，缺少 */ 结尾");
            return null;
        }
        comment = comment.substring(0, endIndex + 2);

        String[] lines = comment.split("\\n");

        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            line = line.trim();
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
