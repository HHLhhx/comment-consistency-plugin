package com.nju.comment.dto;

import lombok.Data;

@Data
public class MethodData {
    String signature;
    String body;
    String existingComment;

    public MethodData(String signature, String body, String existingComment) {
        this.signature = signature;
        this.body = body;
        this.existingComment = existingComment;
    }
}