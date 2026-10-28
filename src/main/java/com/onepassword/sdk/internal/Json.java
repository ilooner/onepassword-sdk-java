package com.onepassword.sdk.internal;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.onepassword.sdk.DesktopSessionExpiredException;
import com.onepassword.sdk.OnePasswordException;
import com.onepassword.sdk.RateLimitExceededException;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/** Internal wire format shared by all transports. */
public final class Json {
    private static final ObjectMapper MAPPER = createMapper();

    private Json() {}

    private static ObjectMapper createMapper() {
        SimpleModule binary = new SimpleModule();
        // Rust's Vec<u8> uses unsigned JSON integers, not Jackson's default base64.
        binary.addSerializer(byte[].class, new JsonSerializer<>() {
            @Override
            public void serialize(byte[] value, JsonGenerator generator, SerializerProvider provider)
                    throws IOException {
                generator.writeStartArray();
                for (byte b : value) {
                    generator.writeNumber(Byte.toUnsignedInt(b));
                }
                generator.writeEndArray();
            }
        });
        binary.addDeserializer(byte[].class, new JsonDeserializer<>() {
            @Override
            public byte[] deserialize(JsonParser parser, DeserializationContext context) throws IOException {
                JsonNode node = context.readTree(parser);
                if (!node.isArray()) {
                    throw new IOException("Expected a byte array from the SDK core");
                }
                byte[] bytes = new byte[node.size()];
                for (int i = 0; i < bytes.length; i++) {
                    JsonNode element = node.get(i);
                    if (!element.isIntegralNumber() || !element.canConvertToInt()
                            || element.intValue() < 0 || element.intValue() > 255) {
                        throw new IOException("Invalid unsigned byte from the SDK core");
                    }
                    bytes[i] = (byte) element.intValue();
                }
                return bytes;
            }
        });
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .registerModule(binary)
                .setSerializationInclusion(JsonInclude.Include.NON_NULL)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE)
                .enable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS);
    }

    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            // Jackson messages can contain field values, including passwords.
            throw new OnePasswordException("Unable to encode SDK request");
        }
    }

    public static <T> T read(String value, Class<T> type) {
        try {
            return MAPPER.readValue(value, type);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new OnePasswordException("Unable to decode SDK response as " + type.getSimpleName());
        }
    }

    public static <T> T read(String value, TypeReference<T> type) {
        try {
            return MAPPER.readValue(value, type);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new OnePasswordException("Unable to decode SDK response");
        }
    }

    public static JsonNode tree(String value) {
        return read(value, JsonNode.class);
    }

    public static Map<String, Object> parameters(Object... entries) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < entries.length; i += 2) {
            result.put((String) entries[i], entries[i + 1]);
        }
        return result;
    }

    public static String invocation(Object clientId, String method, Map<String, Object> parameters) {
        return write(Map.of("invocation", parameters("clientId", clientId,
                "parameters", Map.of("name", method, "parameters", parameters))));
    }

    public static OnePasswordException error(String serialized) {
        try {
            JsonNode node = MAPPER.readTree(serialized);
            if (node != null && node.isObject()) {
                String name = node.path("name").asText("Unknown");
                String message = node.path("message").asText("The SDK core reported an error");
                return switch (name) {
                    case "DesktopSessionExpired" -> new DesktopSessionExpiredException(message);
                    case "RateLimitExceeded" -> new RateLimitExceededException(message);
                    default -> new OnePasswordException(name, message, null);
                };
            }
        } catch (JsonProcessingException ignored) {
            // Some core versions return plain text errors.
        }
        return new OnePasswordException(serialized);
    }
}
