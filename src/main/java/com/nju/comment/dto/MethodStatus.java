package com.nju.comment.dto;

public enum MethodStatus {
    NEW_METHOD_WITHOUT_COMMENT("NEW_METHOD_WITHOUT_COMMENT"),
    NEW_METHOD_WITH_COMMENT("NEW_METHOD_WITH_COMMENT"),
    UNCHANGED("UNCHANGED"),
    TO_BE_UPDATE("TO_BE_UPDATE"),
    TO_BE_GENERATE("TO_BE_GENERATE"),
    COMMENT_CHANGED("COMMENT_CHANGED"),
    METHOD_CHANGED("METHOD_CHANGED"),
    GENERATING("GENERATING"),
    UNDEFINED("UNDEFINED");

    private final String value;

    MethodStatus(String value) {
        this.value = value;
    }

    @Override
    public String toString() {
        return value;
    }
}
