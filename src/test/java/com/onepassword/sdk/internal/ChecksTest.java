package com.onepassword.sdk.internal;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ChecksTest {
    @Test
    void messageLimitCountsUtf8BytesRatherThanJavaCharacters() {
        String message = "é".repeat(Checks.MESSAGE_LIMIT / 2 + 1);
        assertTrue(message.length() < Checks.MESSAGE_LIMIT);
        assertThrows(IllegalArgumentException.class, () -> Checks.messageBytes(message));
        assertEquals(4, Checks.messageBytes("😀").length);
    }
}
