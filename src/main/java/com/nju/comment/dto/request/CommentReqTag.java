package com.nju.comment.dto.request;

/**
 * 注释请求类型标签，与后端 CommentReqTag 对齐。
 */
public enum CommentReqTag {
    UPDATE_WITH_RAG,
    UPDATE_WITHOUT_RAG,
    GENERATE
}
