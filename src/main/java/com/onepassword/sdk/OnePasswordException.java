package com.onepassword.sdk;

/** An error reported by the SDK core or its Java binding. */
public class OnePasswordException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final String errorName;

    public OnePasswordException(String message) {
        this("Unknown", message, null);
    }

    public OnePasswordException(String message, Throwable cause) {
        this("Unknown", message, cause);
    }

    public OnePasswordException(String errorName, String message, Throwable cause) {
        super(message, cause);
        this.errorName = errorName;
    }

    public String getErrorName() {
        return errorName;
    }
}
