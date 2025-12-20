package com.nju.comment.backend.dto.response;

import lombok.Data;

@Data
public class ApiResponse<T> {
    private boolean success;
    private int code;
    private String message;
    private T data;
    private Long serverTime;
}
