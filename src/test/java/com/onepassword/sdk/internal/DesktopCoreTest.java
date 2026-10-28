package com.onepassword.sdk.internal;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.onepassword.sdk.OnePasswordException;
import com.onepassword.sdk.RateLimitExceededException;
import com.sun.jna.Memory;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.LongByReference;
import com.sun.jna.ptr.PointerByReference;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DesktopCoreTest {
    private static final class Ipc implements DesktopCore.Bindings, AutoCloseable {
        JsonNode request;
        Memory output;
        String response = Json.write(Map.of("success", true, "payload", "41".getBytes(StandardCharsets.UTF_8)));
        int code;
        int freed;

        @Override
        public int op_sdk_ipc_send_message(byte[] message, long length, PointerByReference buffer,
                LongByReference outputLength, LongByReference outputCapacity) {
            assertEquals(message.length, length);
            request = Json.tree(new String(message, StandardCharsets.UTF_8));
            byte[] data = response.getBytes(StandardCharsets.UTF_8);
            output = new Memory(data.length);
            output.write(0, data, 0, data.length);
            buffer.setValue(output);
            outputLength.setValue(data.length);
            outputCapacity.setValue(data.length);
            return code;
        }

        @Override
        public void op_sdk_ipc_free_response(Pointer buffer, long length, long capacity) {
            assertEquals(output, buffer);
            assertEquals(output.size(), length);
            assertEquals(output.size(), capacity);
            freed++;
            output.close();
        }

        @Override
        public void close() {
            if (output != null) {
                output.close();
            }
        }
    }

    @Test
    void wrapsUtf8RequestsWithAccountAndBase64AndFreesResponse() {
        try (Ipc ipc = new Ipc()) {
            DesktopCore core = new DesktopCore("個人", ipc);
            assertEquals("41", core.initClient("{\"integrationName\":\"café\"}"));
            assertEquals("init_client", ipc.request.path("kind").asText());
            assertEquals("個人", ipc.request.path("account_name").asText());
            assertEquals("{\"integrationName\":\"café\"}", new String(Base64.getDecoder()
                    .decode(ipc.request.path("payload").asText()), StandardCharsets.UTF_8));
            assertEquals(1, ipc.freed);
        }
    }

    @Test
    void supportsBase64ResponsesAndReleaseMessages() {
        try (Ipc ipc = new Ipc()) {
            ipc.response = "{\"success\":true,\"payload\":\"bnVsbA==\"}";
            DesktopCore core = new DesktopCore("Account", ipc);
            core.releaseClient("41");
            assertEquals("release_client", ipc.request.path("kind").asText());
            assertEquals(1, ipc.freed);
        }
    }

    @Test
    void convertsCoreErrorsAndFreesTheBuffer() {
        try (Ipc ipc = new Ipc()) {
            ipc.response = Json.write(Map.of("success", false, "payload",
                    "{\"name\":\"RateLimitExceeded\",\"message\":\"Wait\"}".getBytes(StandardCharsets.UTF_8)));
            assertThrows(RateLimitExceededException.class, () -> new DesktopCore("Account", ipc).invoke("{}"));
            assertEquals(1, ipc.freed);
        }
    }

    @Test
    void malformedResponsesAndTransportFailuresStillFreeBuffers() {
        for (String response : new String[] {"bad JSON", "{}", "{\"success\":true,\"payload\":[-1]}"}) {
            try (Ipc ipc = new Ipc()) {
                ipc.response = response;
                assertThrows(OnePasswordException.class, () -> new DesktopCore("Account", ipc).invoke("{}"));
                assertEquals(1, ipc.freed);
            }
        }
        try (Ipc ipc = new Ipc()) {
            ipc.code = -99;
            assertTrue(assertThrows(OnePasswordException.class,
                    () -> new DesktopCore("Account", ipc).invoke("{}")).getMessage().contains("-99"));
            assertEquals(1, ipc.freed);
        }
    }

    @Test
    void clientsDoNotShareDesktopAccountNames() {
        try (Ipc first = new Ipc(); Ipc second = new Ipc()) {
            new DesktopCore("first", first).initClient("{}");
            new DesktopCore("second", second).initClient("{}");
            assertEquals("first", first.request.path("account_name").asText());
            assertEquals("second", second.request.path("account_name").asText());
        }
    }
}
