package com.onepassword.sdk.internal;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.onepassword.sdk.DesktopSessionExpiredException;
import com.onepassword.sdk.OnePasswordException;
import com.onepassword.sdk.spi.Core;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Serializes calls and cleanup, including desktop session renewal. */
public final class Session implements Runnable {
    private final Core core;
    private final boolean desktop;
    private final Map<String, Object> configuration;
    private final List<BigInteger> retired = new ArrayList<>();
    private BigInteger clientId;
    private boolean closed;

    public Session(Core core, Map<String, Object> configuration, boolean desktop) {
        this.core = core;
        this.configuration = new LinkedHashMap<>(configuration);
        this.desktop = desktop;
        clientId = initialize();
    }

    private BigInteger initialize() {
        JsonNode result = Json.tree(core.initClient(Json.write(configuration)));
        if (result == null || !result.isIntegralNumber() || result.bigIntegerValue().signum() < 0
                || result.bigIntegerValue().bitLength() > 64) {
            throw new OnePasswordException("Native core returned an invalid client ID");
        }
        return result.bigIntegerValue();
    }

    public <T> T invoke(String method, Map<String, Object> parameters, Class<T> type) {
        return Json.read(invoke(method, parameters), type);
    }

    public <T> T invoke(String method, Map<String, Object> parameters, TypeReference<T> type) {
        return Json.read(invoke(method, parameters), type);
    }

    public synchronized String invoke(String method, Map<String, Object> parameters) {
        if (closed) {
            throw new IllegalStateException("1Password client is closed");
        }
        try {
            return call(method, parameters);
        } catch (DesktopSessionExpiredException e) {
            if (!desktop) {
                throw e;
            }
            BigInteger previous = clientId;
            clientId = initialize();
            if (!previous.equals(clientId)) {
                retired.add(previous);
            }
            // Retry exactly once. No retries for rate limits or mutations with other errors.
            return call(method, parameters);
        }
    }

    private String call(String method, Map<String, Object> parameters) {
        String request = Json.invocation(clientId, method, parameters);
        Checks.messageBytes(request);
        return core.invoke(request);
    }

    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        configuration.clear();
        retired.add(clientId);
        OnePasswordException failure = null;
        for (BigInteger id : retired) {
            try {
                core.releaseClient(id.toString());
            } catch (RuntimeException e) {
                if (failure == null) {
                    failure = new OnePasswordException("Unable to release a 1Password client", e);
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        retired.clear();
        if (failure != null) {
            throw failure;
        }
    }

    @Override
    public void run() {
        try {
            close();
        } catch (RuntimeException ignored) {
            // Cleaner callbacks cannot report to a caller. Explicit close propagates failures.
        }
    }
}
