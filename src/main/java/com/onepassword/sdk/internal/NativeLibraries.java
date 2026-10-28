package com.onepassword.sdk.internal;

import com.onepassword.sdk.OnePasswordException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;

/** Locates native libraries without downloading or executing external programs. */
public final class NativeLibraries {
    private NativeLibraries() {}

    public static String platform() {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        String architecture = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
        String family;
        if (os.contains("mac") || os.contains("darwin")) {
            family = "macos";
        } else if (os.contains("windows")) {
            family = "windows";
        } else if (os.contains("linux")) {
            family = "linux";
        } else {
            throw new OnePasswordException("Unsupported operating system: " + os);
        }
        String arch = switch (architecture) {
            case "aarch64", "arm64" -> "aarch64";
            case "x86_64", "amd64" -> "x86_64";
            default -> throw new OnePasswordException("Unsupported architecture: " + architecture);
        };
        return family + "-" + arch;
    }

    public static Path core() {
        String configured = System.getProperty("onepassword.native.path");
        if (configured == null || configured.isBlank()) {
            configured = System.getenv("ONEPASSWORD_NATIVE_LIBRARY");
        }
        if (configured != null && !configured.isBlank()) {
            return requireFile(Path.of(configured));
        }
        String platform = platform();
        String name = platform.startsWith("macos") ? "libop_uniffi_core.dylib"
                : platform.startsWith("windows") ? "op_uniffi_core.dll" : "libop_uniffi_core.so";
        String resource = "/native/" + platform + "/" + name;
        try (InputStream stream = NativeLibraries.class.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new OnePasswordException("1Password native core is missing. Add the native-"
                        + platform + " JAR, or set onepassword.native.path / ONEPASSWORD_NATIVE_LIBRARY "
                        + "to the library downloaded by ./gradlew downloadNative.");
            }
            Path directory = Files.createTempDirectory("onepassword-sdk-");
            Path library = directory.resolve(name);
            directory.toFile().deleteOnExit();
            library.toFile().deleteOnExit();
            Files.copy(stream, library, StandardCopyOption.REPLACE_EXISTING);
            return library;
        } catch (IOException e) {
            throw new OnePasswordException("Unable to extract the 1Password native core", e);
        }
    }

    public static Path desktop() {
        String configured = System.getProperty("onepassword.desktop.library.path");
        if (configured != null && !configured.isBlank()) {
            return requireFile(Path.of(configured));
        }
        String home = System.getProperty("user.home");
        String platform = platform();
        List<Path> candidates;
        if (platform.startsWith("macos")) {
            candidates = List.of(
                    Path.of("/Applications/1Password.app/Contents/Frameworks/libop_sdk_ipc_client.dylib"),
                    Path.of(home, "Applications/1Password.app/Contents/Frameworks/libop_sdk_ipc_client.dylib"));
        } else if (platform.startsWith("linux")) {
            candidates = List.of(Path.of("/usr/bin/1password/libop_sdk_ipc_client.so"),
                    Path.of("/opt/1Password/libop_sdk_ipc_client.so"),
                    Path.of("/snap/bin/1password/libop_sdk_ipc_client.so"));
        } else {
            candidates = List.of(Path.of(home, "AppData/Local/1Password/op_sdk_ipc_client.dll"),
                    Path.of("C:/Program Files/1Password/app/8/op_sdk_ipc_client.dll"),
                    Path.of("C:/Program Files (x86)/1Password/app/8/op_sdk_ipc_client.dll"),
                    Path.of(home, "AppData/Local/1Password/app/8/op_sdk_ipc_client.dll"));
        }
        return candidates.stream().filter(Files::isRegularFile).findFirst().orElseThrow(() ->
                new OnePasswordException("1Password desktop application not found. Install it and enable "
                        + "Settings > Developer > Integrate with other apps."));
    }

    private static Path requireFile(Path path) {
        if (!Files.isRegularFile(path)) {
            throw new OnePasswordException("Native library does not exist: " + path);
        }
        return path.toAbsolutePath();
    }
}
