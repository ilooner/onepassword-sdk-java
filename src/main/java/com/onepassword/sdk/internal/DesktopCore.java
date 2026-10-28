package com.onepassword.sdk.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.onepassword.sdk.OnePasswordException;
import com.onepassword.sdk.spi.Core;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.LongByReference;
import com.sun.jna.ptr.PointerByReference;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;

/** Desktop IPC transport for the supported 64-bit platforms. */
public final class DesktopCore implements Core {
    private final String accountName;
    private final Bindings lib;

    public DesktopCore(String accountName) {
        this(accountName, NativeLibraries.desktop());
    }

    public DesktopCore(String accountName, Path path) {
        this.accountName = Checks.notBlank(accountName, "accountName");
        if (Native.SIZE_T_SIZE != Long.BYTES) {
            throw new OnePasswordException("Desktop integration requires a 64-bit JVM");
        }
        try {
            lib = Native.load(path.toAbsolutePath().toString(), Bindings.class);
        } catch (LinkageError e) {
            throw new OnePasswordException("Unable to load the 1Password desktop IPC library", e);
        }
    }

    DesktopCore(String accountName, Bindings bindings) {
        this.accountName = Checks.notBlank(accountName, "accountName");
        this.lib = java.util.Objects.requireNonNull(bindings, "bindings");
    }

    @Override
    public String initClient(String configuration) {
        return call("init_client", configuration);
    }

    @Override
    public String invoke(String invocation) {
        return call("invoke", invocation);
    }

    @Override
    public String invokeSync(String invocation) {
        return NativeCore.shared().invokeSync(invocation);
    }

    @Override
    public void releaseClient(String clientId) {
        call("release_client", clientId);
    }

    private String call(String kind, String payload) {
        byte[] bytes = Checks.messageBytes(payload);
        byte[] request = Json.write(Map.of("kind", kind, "account_name", accountName,
                "payload", Base64.getEncoder().encodeToString(bytes))).getBytes(StandardCharsets.UTF_8);
        PointerByReference output = new PointerByReference();
        LongByReference length = new LongByReference();
        LongByReference capacity = new LongByReference();
        int code = lib.op_sdk_ipc_send_message(request, request.length, output, length, capacity);
        Pointer buffer = output.getValue();
        try {
            if (code != 0) {
                throw returnCodeError(code);
            }
            long size = length.getValue();
            // An IPC envelope contains a JSON array of bytes, up to four characters per byte.
            if (buffer == null || size < 0 || size > Checks.MESSAGE_LIMIT * 5L || capacity.getValue() < size) {
                throw new OnePasswordException("Desktop app returned an invalid response buffer");
            }
            JsonNode response = Json.tree(new String(buffer.getByteArray(0, (int) size), StandardCharsets.UTF_8));
            if (response == null || !response.path("success").isBoolean()) {
                throw new OnePasswordException("Desktop app returned an invalid response envelope");
            }
            JsonNode content = response.path("payload");
            byte[] decoded;
            if (content.isArray()) {
                decoded = Json.read(content.toString(), byte[].class);
            } else if (content.isTextual()) {
                try {
                    decoded = Base64.getDecoder().decode(content.textValue());
                } catch (IllegalArgumentException e) {
                    throw new OnePasswordException("Desktop app returned invalid base64");
                }
            } else {
                throw new OnePasswordException("Desktop app returned an invalid payload");
            }
            if (decoded.length > Checks.MESSAGE_LIMIT) {
                throw new OnePasswordException("Desktop response exceeds the 50 MiB limit");
            }
            String result = new String(decoded, StandardCharsets.UTF_8);
            if (!response.get("success").booleanValue()) {
                throw Json.error(result);
            }
            return result;
        } finally {
            if (buffer != null) {
                lib.op_sdk_ipc_free_response(buffer, length.getValue(), capacity.getValue());
            }
        }
    }

    static OnePasswordException returnCodeError(int code) {
        boolean mac = NativeLibraries.platform().startsWith("macos");
        if (code == (mac ? -3 : -2)) {
            return new OnePasswordException("Desktop app connection is closed. Enable Settings > Developer > Integrate with other apps.");
        }
        if (code == (mac ? -7 : -5)) {
            return new OnePasswordException("Desktop app disconnected. Make sure 1Password is running.");
        }
        return new OnePasswordException("1Password desktop IPC failed (return code " + code + ")");
    }

    public interface Bindings extends com.sun.jna.Library {
        int op_sdk_ipc_send_message(byte[] message, long length, PointerByReference output,
                LongByReference outputLength, LongByReference outputCapacity);
        void op_sdk_ipc_free_response(Pointer buffer, long length, long capacity);
    }
}
