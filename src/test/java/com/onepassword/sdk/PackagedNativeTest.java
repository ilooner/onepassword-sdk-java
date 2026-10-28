package com.onepassword.sdk;

import static org.junit.jupiter.api.Assertions.*;

import com.onepassword.sdk.internal.NativeLibraries;
import com.onepassword.sdk.model.PasswordRecipe;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Tag("native-packaged")
@Timeout(20)
class PackagedNativeTest {
    @Test
    void publicUtilitiesLoadTheCoreFromThePlatformJar() {
        assertNull(System.getProperty("onepassword.native.path"));
        assertNotNull(NativeLibraries.class.getResource("/native/" + NativeLibraries.platform() + "/"
                + System.mapLibraryName("op_uniffi_core")));
        Secrets.validateSecretReference("op://vault/item/password");
        assertTrue(Secrets.generatePassword(PasswordRecipe.pin(6)).password().matches("[0-9]{6}"));
    }
}
