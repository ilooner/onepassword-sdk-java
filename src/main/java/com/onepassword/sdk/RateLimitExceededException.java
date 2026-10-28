package com.onepassword.sdk;

/** The account's request limit was exceeded. This error is not automatically retried. */
public final class RateLimitExceededException extends OnePasswordException {
    private static final long serialVersionUID = 1L;

    public RateLimitExceededException(String message) {
        super("RateLimitExceeded", message, null);
    }
}
