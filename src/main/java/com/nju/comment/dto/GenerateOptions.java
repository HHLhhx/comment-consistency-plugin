package com.nju.comment.dto;

import com.nju.comment.dto.request.CommentReqTag;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class GenerateOptions {
    String modelName;
    CommentReqTag tag;
}
