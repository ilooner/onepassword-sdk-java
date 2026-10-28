package com.onepassword.sdk;

import static org.junit.jupiter.api.Assertions.*;

import com.onepassword.sdk.internal.Json;
import com.onepassword.sdk.internal.NativeCore;
import com.onepassword.sdk.model.PasswordRecipe;
import com.onepassword.sdk.model.SeparatorType;
import com.onepassword.sdk.model.WordListType;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Tag("native")
@Timeout(20)
class NativeCoreTest {
    private NativeCore core() {
        return new NativeCore(Path.of(System.getProperty("onepassword.native.path")));
    }

    @Test
    void loadsRealAbiAndGeneratesAllPasswordVariants() {
        SecretUtilities utilities = new SecretUtilities(core());
        String pin = utilities.generatePassword(PasswordRecipe.pin(8)).password();
        assertTrue(pin.matches("[0-9]{8}"));
        String random = utilities.generatePassword(PasswordRecipe.random(32, true, true)).password();
        assertEquals(32, random.length());
        assertTrue(random.chars().anyMatch(Character::isDigit));
        String memorable = utilities.generatePassword(PasswordRecipe.memorable(4, SeparatorType.HYPHENS,
                false, WordListType.FULL_WORDS)).password();
        assertEquals(4, memorable.split("-").length);
    }

    @Test
    void validatesReferencesAndLiftsRealRustErrors() {
        SecretUtilities utilities = new SecretUtilities(core());
        utilities.validateSecretReference("op://vault/item/password");
        assertThrows(OnePasswordException.class, () -> utilities.validateSecretReference("invalid"));
        assertThrows(OnePasswordException.class, () -> utilities.generatePassword(PasswordRecipe.pin(0)));
    }

    @Test
    void pollsCompletesAndFreesAnActualRustFuture() {
        NativeCore core = core();
        String invocation = Json.invocation(null, "ValidateSecretReference",
                Map.of("secret_reference", "op://vault/item/password"));
        for (int i = 0; i < 20; i++) {
            assertEquals("", core.invoke(invocation));
        }
        assertThrows(OnePasswordException.class, () -> core.invoke(Json.invocation(null,
                "ValidateSecretReference", Map.of("secret_reference", "invalid"))));
    }

    @Test
    void invalidTokenIsRejectedLocallyDuringAsyncInitialization() {
        OnePasswordException error = assertThrows(OnePasswordException.class,
                () -> Client.builder().serviceAccountToken("not-a-service-account-token").core(core()).build());
        assertFalse(error.getMessage().isBlank());
        assertFalse(error.getMessage().contains("invalid client ID"));
        assertTrue(error.getMessage().toLowerCase(java.util.Locale.ROOT).contains("token"), error::getMessage);
    }

    @Test
    void multipleNativeFuturesCanCompleteConcurrently() throws Exception {
        NativeCore core = core();
        var executor = Executors.newFixedThreadPool(4);
        try {
            Callable<String> call = () -> core.invoke(Json.invocation(null, "GeneratePassword",
                    Map.of("recipe", PasswordRecipe.pin(6))));
            for (var result : executor.invokeAll(List.of(call, call, call, call, call, call))) {
                assertTrue(Json.tree(result.get()).path("password").asText().matches("[0-9]{6}"));
            }
        } finally {
            executor.shutdownNow();
        }
    }
}
