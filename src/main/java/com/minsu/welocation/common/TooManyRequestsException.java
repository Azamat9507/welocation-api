package com.minsu.welocation.common;

import lombok.Getter;

@Getter
public class TooManyRequestsException extends RuntimeException {

    private final long retryAfterSeconds;

    public TooManyRequestsException(long retryAfterSeconds) {
        super("too many attempts, try again later");
        this.retryAfterSeconds = retryAfterSeconds;
    }
}
