package com.nju.comment.plugin;

public class MethodData {
    String signature;
    String body;
    String existingComment;

    MethodData(String signature, String body, String existingComment) {
        this.signature = signature;
        this.body = body;
        this.existingComment = existingComment;
    }

    @Override
    public String toString() {
        return "MethodData{" +
                "signature='" + signature + '\'' +
                ", body='" + body + '\'' +
                ", existingComment='" + existingComment + '\'' +
                '}';
    }
}