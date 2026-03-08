package com.nju.comment.client;

import com.nju.comment.dto.request.ApiKeyRequest;
import com.nju.comment.dto.request.CommentRequest;
import com.nju.comment.dto.request.LoginRequest;
import com.nju.comment.dto.request.RegisterRequest;
import com.nju.comment.dto.response.AuthResponse;
import com.nju.comment.dto.response.CommentResponse;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public interface CommentClient {

    CompletableFuture<CommentResponse> generateComment(CommentRequest request);

    CompletableFuture<Void> cancelRequest(String requestId);

    CompletableFuture<List<String>> getAvailableModels();

    CompletableFuture<AuthResponse> login(LoginRequest request);

    CompletableFuture<AuthResponse> register(RegisterRequest request);

    CompletableFuture<Void> logout();

    CompletableFuture<Void> saveApiKey(ApiKeyRequest request);

    CompletableFuture<String> checkApiKey();

    CompletableFuture<Void> deleteApiKey();

    void shutdown();
}
