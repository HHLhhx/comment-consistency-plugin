package com.nju.comment.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CommentResponse {

    private boolean success;

    private String generatedComment;

    @Builder.Default
    private List<String> alternativeComments = List.of();

    private String modelUsed;

    private Long processingTimeMs;

    private String requestId;

    private Instant timestamp;

    private String errorMessage;

    @Builder.Default
    private Map<String, Object> metadata = Map.of();
}
