package com.onepassword.sdk;

import com.fasterxml.jackson.databind.JsonNode;
import com.onepassword.sdk.internal.Json;
import com.onepassword.sdk.spi.Core;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

final class RecordingCore implements Core {
    final List<JsonNode> configurations = new ArrayList<>();
    final List<JsonNode> invocations = new ArrayList<>();
    final List<String> released = new ArrayList<>();
    Function<JsonNode, String> handler = request -> "null";
    String firstId = "41";

    @Override
    public String initClient(String configuration) {
        configurations.add(Json.tree(configuration));
        return configurations.size() == 1 ? firstId : "42";
    }

    @Override
    public String invoke(String invocation) {
        JsonNode request = Json.tree(invocation);
        invocations.add(request);
        return handler.apply(request);
    }

    @Override
    public String invokeSync(String invocation) {
        return invoke(invocation);
    }

    @Override
    public void releaseClient(String clientId) {
        released.add(clientId);
    }

    Client client() {
        return Client.builder().serviceAccountToken("test-token").integration("Tests", "1.0").core(this).build();
    }
}
