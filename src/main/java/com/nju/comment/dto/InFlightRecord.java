package com.nju.comment.dto;

import com.nju.comment.dto.response.CommentResponse;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.concurrent.CompletableFuture;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class InFlightRecord {
    String requestId;
    CompletableFuture<CommentResponse> future;
    String contentFingerprint;
}
