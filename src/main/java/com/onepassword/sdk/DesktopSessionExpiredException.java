package com.onepassword.sdk;

/** The desktop application's authorization session has expired. */
public final class DesktopSessionExpiredException extends OnePasswordException {
    private static final long serialVersionUID = 1L;

    public DesktopSessionExpiredException(String message) {
        super("DesktopSessionExpired", message, null);
    }
}
