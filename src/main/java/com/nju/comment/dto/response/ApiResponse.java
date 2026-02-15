package com.nju.comment.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 与后端 ApiResponse 对齐的通用响应包装。
 * 使用 {@link JsonNode} 持有 data 节点，由调用方按需反序列化为具体类型。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ApiResponse {

    private boolean success;

    private int code;

    private String message;

    /** 原始 data 节点，调用方自行转换 */
    private JsonNode data;

    private long serverTime;
}
