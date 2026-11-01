package com.onepassword.sdk;

import com.onepassword.sdk.internal.BuildInfo;
import com.onepassword.sdk.internal.Checks;
import com.onepassword.sdk.internal.DesktopCore;
import com.onepassword.sdk.internal.Json;
import com.onepassword.sdk.internal.NativeCore;
import com.onepassword.sdk.internal.Session;
import com.onepassword.sdk.spi.Core;
import java.lang.ref.Cleaner;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Authenticated 1Password client. Use try-with-resources to release native state.
 * Calls on a client are thread safe and serialized; use separate clients for parallel requests.
 */
public final class Client implements AutoCloseable {
    public static final String VERSION = BuildInfo.VERSION;
    private static final Cleaner CLEANER = Cleaner.create();
    private final Session session;
    private final Cleaner.Cleanable cleanable;
    private final Secrets secrets;
    private final Items items;
    private final Vaults vaults;
    private final Groups groups;
    private final Environments environments;

    private Client(Builder builder) {
        boolean desktop = builder.accountName != null;
        Core core = builder.core != null ? builder.core
                : desktop ? new DesktopCore(builder.accountName) : NativeCore.shared();
        Map<String, Object> config = Json.parameters(
                "programmingLanguage", "Java", "sdkVersion", "0040201",
                "integrationName", builder.integrationName, "integrationVersion", builder.integrationVersion,
                "requestLibraryName", "reqwest", "requestLibraryVersion", "0.11.24",
                "os", operatingSystemName(),
                "osVersion", System.getProperty("os.version"), "architecture", System.getProperty("os.arch"));
        if (!desktop) {
            config.put("serviceAccountToken", builder.serviceAccountToken);
        }
        session = new Session(core, config, desktop);
        cleanable = CLEANER.register(this, session);
        secrets = new Secrets(this, session);
        items = new Items(this, session);
        vaults = new Vaults(this, session);
        groups = new Groups(this, session);
        environments = new Environments(this, session);
    }

    private static String operatingSystemName() {
        String operatingSystem = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        // The native user-agent builder expects OS families, not Java's display names.
        if (operatingSystem.startsWith("mac") || operatingSystem.equals("darwin")) {
            return "darwin";
        }
        if (operatingSystem.startsWith("windows")) {
            return "windows";
        }
        return operatingSystem;
    }

    public static Builder builder() {
        return new Builder();
    }

    public Secrets secrets() { return secrets; }
    public Items items() { return items; }
    public Vaults vaults() { return vaults; }
    public Groups groups() { return groups; }
    public Environments environments() { return environments; }

    /** Waits for any in-flight call, then releases all native sessions. Idempotent. */
    @Override
    public void close() {
        try {
            session.close();
        } finally {
            cleanable.clean();
        }
    }

    /** Configures an authenticated client. Authentication occurs when build is called. */
    public static final class Builder {
        private String serviceAccountToken;
        private String accountName;
        private String integrationName = "Unknown";
        private String integrationVersion = "Unknown";
        private Core core;

        private Builder() {}

        public Builder serviceAccountToken(String token) {
            serviceAccountToken = Checks.notBlank(token, "serviceAccountToken");
            return this;
        }

        public Builder desktopAccount(String account) {
            accountName = Checks.notBlank(account, "accountName");
            return this;
        }

        public Builder integration(String name, String version) {
            integrationName = Checks.notBlank(name, "integrationName");
            integrationVersion = Checks.notBlank(version, "integrationVersion");
            return this;
        }

        /** Supplies a custom transport, primarily for tests and embedded integrations. */
        public Builder core(Core value) {
            core = Objects.requireNonNull(value, "core");
            return this;
        }

        public Client build() {
            if ((serviceAccountToken == null) == (accountName == null)) {
                throw new IllegalArgumentException("Specify exactly one of serviceAccountToken or desktopAccount");
            }
            return new Client(this);
        }
    }
}
