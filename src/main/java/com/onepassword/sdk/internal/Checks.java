package com.onepassword.sdk.internal;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Argument and FFI limits. */
public final class Checks {
    public static final int MESSAGE_LIMIT = 50 * 1024 * 1024;

    private Checks() {}

    public static String notBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    public static byte[] messageBytes(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MESSAGE_LIMIT) {
            throw new IllegalArgumentException("SDK message exceeds the 50 MiB UTF-8 limit");
        }
        return bytes;
    }

    public static long unsignedInt(long value, String name) {
        if (value < 0 || value > 0xffff_ffffL) {
            throw new IllegalArgumentException(name + " must be an unsigned 32-bit integer");
        }
        return value;
    }
}
