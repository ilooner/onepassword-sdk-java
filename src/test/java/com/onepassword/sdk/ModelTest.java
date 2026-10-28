package com.onepassword.sdk;

import static org.junit.jupiter.api.Assertions.*;

import com.onepassword.sdk.internal.Json;
import com.onepassword.sdk.model.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ModelTest {
    @Test
    void itemRoundTripPreservesFieldsTimestampsAndVersions() {
        Item item = Fixtures.item();
        assertEquals(ItemCategory.LOGIN, item.category());
        assertEquals(Instant.parse("2024-01-02T03:04:05.123Z"), item.createdAt());
        assertEquals(item, Json.read(Json.write(item), Item.class));
        Item renamed = item.toBuilder().title("Renamed").build();
        assertEquals("Example", item.title());
        assertEquals("Renamed", renamed.title());
        assertEquals(3, renamed.version());
        assertThrows(UnsupportedOperationException.class, () -> item.fields().clear());
    }

    @Test
    void binaryFieldsUseUnsignedArraysAndDefensiveCopies() {
        byte[] content = {0, 1, 127, (byte) 128, (byte) 255};
        DocumentCreateParams doc = new DocumentCreateParams("test", content);
        content[0] = 5;
        doc.content()[1] = 9;
        assertArrayEquals(new byte[] {0, 1, 127, (byte) 128, (byte) 255}, doc.content());
        assertEquals("[0,1,127,128,255]", Json.tree(Json.write(doc)).get("content").toString());
        assertEquals(doc, Json.read(Json.write(doc), DocumentCreateParams.class));
        assertEquals(doc.hashCode(), Json.read(Json.write(doc), DocumentCreateParams.class).hashCode());
        assertThrows(OnePasswordException.class, () -> Json.read("[256]", byte[].class));
        assertThrows(OnePasswordException.class, () -> Json.read("[-1]", byte[].class));
        assertThrows(OnePasswordException.class, () -> Json.read("[1.5]", byte[].class));
    }

    @Test
    void recipeTagsMatchTheNativeProtocol() {
        PasswordRecipe recipe = PasswordRecipe.random(20, true, false);
        assertEquals(Json.tree("""
                {"type":"Random","parameters":{"includeDigits":true,"includeSymbols":false,"length":20}}
                """), Json.tree(Json.write(recipe)));
        assertEquals(recipe, Json.read(Json.write(recipe), PasswordRecipe.class));
        assertEquals("Pin", Json.tree(Json.write(PasswordRecipe.pin(6))).path("type").asText());
        PasswordRecipe memorable = PasswordRecipe.memorable(4, SeparatorType.HYPHENS, false, WordListType.FULL_WORDS);
        assertEquals(memorable, Json.read(Json.write(memorable), PasswordRecipe.class));
    }

    @Test
    void taggedVariantsSurviveErasedCollectionsAndNestedProperties() {
        String filters = Json.write(Map.of("filters", List.of(ItemListFilter.byState(true, false))));
        assertEquals("ByState", Json.tree(filters).at("/filters/0/type").asText());
        ItemShareParams share = new ItemShareParams(List.of(ValidRecipient.email("test@example.com"),
                ValidRecipient.domain("example.com")), ItemShareDuration.ONE_DAY, false);
        assertEquals(share, Json.read(Json.write(share), ItemShareParams.class));
        ItemField field = new ItemField("otp", "OTP", null, ItemFieldType.TOTP, "otpauth://example",
                new ItemFieldDetails.Otp(new OtpFieldDetails("123456", null)));
        assertEquals(field, Json.read(Json.write(field), ItemField.class));
        assertEquals("Otp", Json.tree(Json.write(field)).at("/details/type").asText());
    }

    @Test
    void batchResponsesPreserveIndividualErrorsAndSuccessfulDeletes() {
        ResolveAllResponse response = Json.read("""
                {"individualResponses":{
                  "op://v/i/f":{"content":{"secret":"hidden","itemId":"i","vaultId":"v"}},
                  "op://v/missing/f":{"error":{"type":"itemNotFound"}},
                  "bad":{"error":{"type":"parsing","message":"invalid reference"}}}}
                """, ResolveAllResponse.class);
        assertTrue(response.individualResponses().get("op://v/i/f").isSuccess());
        assertEquals("hidden", response.individualResponses().get("op://v/i/f").content().secret());
        assertInstanceOf(ResolveReferenceError.ItemNotFound.class,
                response.individualResponses().get("op://v/missing/f").error());
        assertInstanceOf(ResolveReferenceError.Parsing.class, response.individualResponses().get("bad").error());
        ItemsDeleteAllResponse deleted = Json.read("""
                {"individualResponses":{"a":{},"b":{"error":{"type":"itemStatusPermissionError"}}}}
                """, ItemsDeleteAllResponse.class);
        assertTrue(deleted.individualResponses().get("a").isSuccess());
        assertFalse(deleted.individualResponses().get("b").isSuccess());
        assertThrows(IllegalArgumentException.class, () -> new Response<>("data", "error"));
    }

    @Test
    void unknownFieldsAreIgnoredAndUnsupportedEnumValuesHaveAnExplicitFallback() {
        String newer = Fixtures.ITEM.replace("\"Login\"", "\"FutureCategory\"")
                .replace("\"version\":3", "\"version\":4294967295,\"newProperty\":true");
        Item item = Json.read(newer, Item.class);
        assertEquals(ItemCategory.UNSUPPORTED, item.category());
        assertEquals(4294967295L, item.version());
        assertThrows(IllegalArgumentException.class, () -> new FileAttributes("file", "id", -1));
        assertThrows(IllegalArgumentException.class, () -> new FileAttributes("file", "id", 4294967296L));
    }

    @Test
    void nullablePropertiesAndCollectionSnapshotsArePreserved() {
        List<String> tags = new ArrayList<>(List.of("a"));
        ItemCreateParams params = ItemCreateParams.builder().category(ItemCategory.LOGIN)
                .vaultId("vault").title("title").tags(tags).build();
        tags.add("b");
        assertEquals(List.of("a"), params.tags());
        assertFalse(Json.tree(Json.write(params)).has("document"));
        assertFalse(Json.tree(Json.write(new VaultUpdateParams(null, "description"))).has("title"));
    }

    @Test
    void modelToStringAndJsonFailuresDoNotExposeSecrets() {
        assertFalse(Fixtures.item().toString().contains("secret"));
        assertFalse(new GeneratePasswordResponse("very-secret").toString().contains("very-secret"));
        assertFalse(new EnvironmentVariable("KEY", "very-secret", true).toString().contains("very-secret"));
        String message = assertThrows(OnePasswordException.class,
                () -> Json.read("{\"very-secret\": malformed}", Item.class)).getMessage();
        assertFalse(message.contains("very-secret"));
    }

    @Test
    void typedAndUnstructuredErrorsRetainUsefulInformation() {
        assertInstanceOf(RateLimitExceededException.class,
                Json.error("{\"name\":\"RateLimitExceeded\",\"message\":\"Wait\"}"));
        assertInstanceOf(DesktopSessionExpiredException.class,
                Json.error("{\"name\":\"DesktopSessionExpired\",\"message\":\"Expired\"}"));
        assertEquals("NewError", Json.error("{\"name\":\"NewError\",\"message\":\"Message\"}").getErrorName());
        assertEquals("plain error", Json.error("plain error").getMessage());
    }
}
