package com.nju.comment.pojo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class MethodContext {
    String oldMethod;
    String oldComment;
    String newMethod;
}