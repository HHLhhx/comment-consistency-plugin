package com.nju.comment.plugin;

import lombok.Data;

@Data
public class MethodData {
    String signature;
    String body;
    String existingComment;

    MethodData(String signature, String body, String existingComment) {
        this.signature = signature;
        this.body = body;
        this.existingComment = existingComment;
    }
}