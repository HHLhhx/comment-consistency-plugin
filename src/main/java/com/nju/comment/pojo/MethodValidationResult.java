package com.nju.comment.pojo;

import java.time.Instant;

import lombok.Getter;

@Getter
public final class MethodValidationResult {

    private final boolean valid;
    private final String invalidReason;
    private final Instant validatedAt;
    private final long sourceStamp;

    private MethodValidationResult(boolean valid, String invalidReason, Instant validatedAt, long sourceStamp) {
        this.valid = valid;
        this.invalidReason = invalidReason;
        this.validatedAt = validatedAt;
        this.sourceStamp = sourceStamp;
    }

    public static MethodValidationResult valid(long sourceStamp) {
        return new MethodValidationResult(true, null, Instant.now(), sourceStamp);
    }

    public static MethodValidationResult invalid(String invalidReason, long sourceStamp) {
        return new MethodValidationResult(false, invalidReason, Instant.now(), sourceStamp);
    }

    public boolean matchesSourceStamp(long sourceStamp) {
        return this.sourceStamp == sourceStamp;
    }
}
