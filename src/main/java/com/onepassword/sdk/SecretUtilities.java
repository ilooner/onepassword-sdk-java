package com.onepassword.sdk;

import com.onepassword.sdk.internal.Checks;
import com.onepassword.sdk.internal.Json;
import com.onepassword.sdk.internal.NativeCore;
import com.onepassword.sdk.model.GeneratePasswordResponse;
import com.onepassword.sdk.model.PasswordRecipe;
import com.onepassword.sdk.spi.Core;
import java.util.Map;
import java.util.Objects;

/** Offline secret-reference validation and cryptographically secure password generation. */
public final class SecretUtilities {
    private final Core core;

    public SecretUtilities() {
        this(NativeCore.shared());
    }

    public SecretUtilities(Core core) {
        this.core = Objects.requireNonNull(core, "core");
    }

    /** Throws OnePasswordException if the reference has invalid syntax. Does not resolve a secret. */
    public void validateSecretReference(String reference) {
        invoke("ValidateSecretReference", Map.of("secret_reference", Checks.notBlank(reference, "reference")));
    }

    public GeneratePasswordResponse generatePassword(PasswordRecipe recipe) {
        return Json.read(invoke("GeneratePassword", Map.of("recipe", Objects.requireNonNull(recipe, "recipe"))),
                GeneratePasswordResponse.class);
    }

    private String invoke(String method, Map<String, Object> params) {
        String message = Json.invocation(null, method, params);
        Checks.messageBytes(message);
        return core.invokeSync(message);
    }
}
