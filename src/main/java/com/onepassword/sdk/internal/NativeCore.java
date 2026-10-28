package com.onepassword.sdk.internal;

import com.onepassword.sdk.OnePasswordException;
import com.onepassword.sdk.spi.Core;
import com.sun.jna.Callback;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/** JNA bindings to the pinned UniFFI contract (version 25). */
public final class NativeCore implements Core {
    private static final AtomicLong NEXT_CALLBACK = new AtomicLong();
    private static final ConcurrentMap<Long, ArrayBlockingQueue<Byte>> PENDING = new ConcurrentHashMap<>();
    // A static callback must remain reachable even after an interrupted Java call.
    private static final Continuation CONTINUATION = (data, code) -> {
        ArrayBlockingQueue<Byte> pending = PENDING.remove(Pointer.nativeValue(data));
        if (pending != null) {
            pending.offer(code);
        }
    };
    private final Bindings lib;

    private static volatile NativeCore shared;

    public static synchronized NativeCore shared() {
        if (shared == null) {
            shared = new NativeCore(NativeLibraries.core());
        }
        return shared;
    }

    public NativeCore(Path library) {
        try {
            lib = Native.load(library.toAbsolutePath().toString(), Bindings.class);
            if (lib.ffi_op_uniffi_core_uniffi_contract_version() != 25
                    || Short.toUnsignedInt(lib.uniffi_op_uniffi_core_checksum_func_init_client()) != 45066
                    || Short.toUnsignedInt(lib.uniffi_op_uniffi_core_checksum_func_invoke()) != 29143
                    || Short.toUnsignedInt(lib.uniffi_op_uniffi_core_checksum_func_invoke_sync()) != 49373
                    || Short.toUnsignedInt(lib.uniffi_op_uniffi_core_checksum_func_release_client()) != 57155) {
                throw new OnePasswordException("Incompatible 1Password native core ABI; use the pinned native artifact");
            }
        } catch (LinkageError e) {
            throw new OnePasswordException("Unable to load the 1Password native core for this platform", e);
        }
    }

    @Override
    public String initClient(String configuration) {
        return await(lib.uniffi_op_uniffi_core_fn_func_init_client(lower(configuration)));
    }

    @Override
    public String invoke(String invocation) {
        return await(lib.uniffi_op_uniffi_core_fn_func_invoke(lower(invocation)));
    }

    @Override
    public String invokeSync(String invocation) {
        CallStatus status = new CallStatus();
        RustBuffer result = lib.uniffi_op_uniffi_core_fn_func_invoke_sync(lower(invocation), status);
        check(status);
        return consume(result);
    }

    @Override
    public void releaseClient(String clientId) {
        CallStatus status = new CallStatus();
        lib.uniffi_op_uniffi_core_fn_func_release_client(lower(clientId), status);
        check(status);
    }

    private String await(Pointer future) {
        if (future == null) {
            throw new OnePasswordException("Native core returned a null future");
        }
        long callbackId = 0;
        try {
            while (true) {
                ArrayBlockingQueue<Byte> ready = new ArrayBlockingQueue<>(1);
                callbackId = NEXT_CALLBACK.incrementAndGet();
                PENDING.put(callbackId, ready);
                lib.ffi_op_uniffi_core_rust_future_poll_rust_buffer(future, CONTINUATION, new Pointer(callbackId));
                byte code = ready.take();
                if (code == 0) {
                    break;
                }
                if (code != 1) {
                    throw new OnePasswordException("Native core returned an invalid future poll status");
                }
            }
            CallStatus status = new CallStatus();
            RustBuffer result = lib.ffi_op_uniffi_core_rust_future_complete_rust_buffer(future, status);
            check(status);
            return consume(result);
        } catch (InterruptedException e) {
            lib.ffi_op_uniffi_core_rust_future_cancel_rust_buffer(future);
            Thread.currentThread().interrupt();
            throw new OnePasswordException("Interrupted while waiting for the 1Password core", e);
        } finally {
            PENDING.remove(callbackId);
            lib.ffi_op_uniffi_core_rust_future_free_rust_buffer(future);
        }
    }

    private RustBuffer lower(String value) {
        byte[] bytes = Checks.messageBytes(value);
        try (Memory memory = new Memory(Math.max(1, bytes.length))) {
            memory.write(0, bytes, 0, bytes.length);
            ForeignBytes foreign = new ForeignBytes();
            foreign.len = bytes.length;
            foreign.data = memory;
            CallStatus status = new CallStatus();
            RustBuffer result = lib.ffi_op_uniffi_core_rustbuffer_from_bytes(foreign, status);
            check(status);
            return result;
        }
    }

    private String consume(RustBuffer buffer) {
        return new String(consumeBytes(buffer), StandardCharsets.UTF_8);
    }

    private byte[] consumeBytes(RustBuffer buffer) {
        try {
            if (buffer.len < 0 || buffer.len > Checks.MESSAGE_LIMIT || buffer.capacity < buffer.len
                    || (buffer.len > 0 && buffer.data == null)) {
                throw new OnePasswordException("Native core returned an invalid or oversized buffer");
            }
            return buffer.len == 0 ? new byte[0] : buffer.data.getByteArray(0, buffer.len);
        } finally {
            if (buffer.data != null) {
                CallStatus status = new CallStatus();
                lib.ffi_op_uniffi_core_rustbuffer_free(buffer, status);
                check(status);
            }
        }
    }

    private void check(CallStatus status) {
        if (status.code == 0) {
            return;
        }
        byte[] bytes = consumeBytes(status.errorBuf);
        if (status.code == 1) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
            if (buffer.remaining() < 8 || buffer.getInt() != 1) {
                throw new OnePasswordException("Native core returned an invalid error variant");
            }
            int length = buffer.getInt();
            if (length < 0 || length != buffer.remaining()) {
                throw new OnePasswordException("Native core returned an invalid error message");
            }
            throw Json.error(new String(bytes, 8, length, StandardCharsets.UTF_8));
        }
        // Panic payloads may contain internal data; do not expose them.
        throw new OnePasswordException("1Password native core failed (status " + status.code + ")");
    }

    @Structure.FieldOrder({"capacity", "len", "data"})
    public static class RustBuffer extends Structure implements Structure.ByValue {
        public int capacity;
        public int len;
        public Pointer data;
    }

    @Structure.FieldOrder({"len", "data"})
    public static class ForeignBytes extends Structure implements Structure.ByValue {
        public int len;
        public Pointer data;
    }

    @Structure.FieldOrder({"code", "errorBuf"})
    public static class CallStatus extends Structure {
        public byte code;
        public RustBuffer errorBuf = new RustBuffer();
    }

    public interface Continuation extends Callback {
        void invoke(Pointer data, byte pollCode);
    }

    @SuppressWarnings("checkstyle:MethodName")
    public interface Bindings extends com.sun.jna.Library {
        int ffi_op_uniffi_core_uniffi_contract_version();
        short uniffi_op_uniffi_core_checksum_func_init_client();
        short uniffi_op_uniffi_core_checksum_func_invoke();
        short uniffi_op_uniffi_core_checksum_func_invoke_sync();
        short uniffi_op_uniffi_core_checksum_func_release_client();
        RustBuffer ffi_op_uniffi_core_rustbuffer_from_bytes(ForeignBytes bytes, CallStatus status);
        void ffi_op_uniffi_core_rustbuffer_free(RustBuffer buffer, CallStatus status);
        Pointer uniffi_op_uniffi_core_fn_func_init_client(RustBuffer configuration);
        Pointer uniffi_op_uniffi_core_fn_func_invoke(RustBuffer invocation);
        RustBuffer uniffi_op_uniffi_core_fn_func_invoke_sync(RustBuffer invocation, CallStatus status);
        void uniffi_op_uniffi_core_fn_func_release_client(RustBuffer clientId, CallStatus status);
        void ffi_op_uniffi_core_rust_future_poll_rust_buffer(Pointer future, Continuation callback, Pointer data);
        void ffi_op_uniffi_core_rust_future_cancel_rust_buffer(Pointer future);
        RustBuffer ffi_op_uniffi_core_rust_future_complete_rust_buffer(Pointer future, CallStatus status);
        void ffi_op_uniffi_core_rust_future_free_rust_buffer(Pointer future);
    }
}
