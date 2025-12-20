package com.nju.comment.client;

import com.nju.comment.backend.dto.request.CommentRequest;
import com.nju.comment.backend.dto.response.CommentResponse;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public interface CommentClient {

    CompletableFuture<CommentResponse> generateComment(CommentRequest request);

    CompletableFuture<List<CommentResponse>> batchGenerateComments(CommentRequest request);

    CompletableFuture<Boolean> health();

    void shutdown();
}
