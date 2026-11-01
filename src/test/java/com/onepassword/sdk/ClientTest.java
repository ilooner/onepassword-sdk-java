package com.onepassword.sdk;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ClientTest {
    @Test
    void requiresExactlyOneAuthenticationMethodBeforeLoadingNativeCode() {
        assertThrows(IllegalArgumentException.class, () -> Client.builder().build());
        assertThrows(IllegalArgumentException.class, () -> Client.builder().serviceAccountToken("token")
                .desktopAccount("account").build());
        assertThrows(IllegalArgumentException.class, () -> Client.builder().serviceAccountToken(" "));
        assertThrows(NullPointerException.class, () -> Client.builder().serviceAccountToken(null));
        assertThrows(IllegalArgumentException.class, () -> Client.builder().integration("", "1"));
    }

    @Test
    void initializationMetadataAndUnsignedClientIdArePreserved() {
        RecordingCore core = new RecordingCore();
        core.firstId = "18446744073709551615";
        core.handler = request -> "\"secret\"";
        try (Client client = core.client()) {
            assertEquals("secret", client.secrets().resolve("op://vault/item/password"));
            var configuration = core.configurations.get(0);
            assertEquals("Java", configuration.path("programmingLanguage").asText());
            assertEquals("test-token", configuration.path("serviceAccountToken").asText());
            assertEquals("Tests", configuration.path("integrationName").asText());
            assertEquals(core.firstId, core.invocations.get(0).at("/invocation/clientId").asText());
        }
        assertEquals(List.of(core.firstId), core.released);
    }

    @ParameterizedTest
    @CsvSource({"Mac OS X, darwin", "macOS, darwin", "Darwin, darwin",
            "Windows 10, windows", "Windows 11, windows", "Windows Server 2022, windows", "Linux, linux"})
    @ResourceLock(Resources.SYSTEM_PROPERTIES)
    void normalizesOperatingSystemForServiceAccountAndDesktopClients(String operatingSystem, String expected) {
        String originalOperatingSystem = System.getProperty("os.name");
        try {
            System.setProperty("os.name", operatingSystem);
            for (boolean desktop : new boolean[]{false, true}) {
                RecordingCore core = new RecordingCore();
                Client.Builder builder = Client.builder().core(core).integration(operatingSystem, "1.0");
                if (desktop) {
                    builder.desktopAccount("account");
                } else {
                    builder.serviceAccountToken("test-token");
                }
                try (Client client = builder.build()) {
                    assertNotNull(client.secrets());
                    var configuration = core.configurations.get(0);
                    assertEquals(expected, configuration.path("os").asText());
                    assertEquals(operatingSystem, configuration.path("integrationName").asText());
                    assertEquals(System.getProperty("os.version"), configuration.path("osVersion").asText());
                    assertEquals(System.getProperty("os.arch"), configuration.path("architecture").asText());
                    if (desktop) {
                        assertFalse(configuration.has("serviceAccountToken"));
                    } else {
                        assertEquals("test-token", configuration.path("serviceAccountToken").asText());
                    }
                    assertEquals(operatingSystem, System.getProperty("os.name"));
                }
                assertEquals(List.of("41"), core.released);
            }
        } finally {
            System.setProperty("os.name", originalOperatingSystem);
        }
    }

    @Test
    void invalidClientIdsAreRejected() {
        for (String id : List.of("null", "\"1\"", "-1", "18446744073709551616", "{}", "1.5")) {
            RecordingCore core = new RecordingCore();
            core.firstId = id;
            assertThrows(OnePasswordException.class, core::client);
        }
    }

    @Test
    void closeIsIdempotentAndAllFacadesRejectLaterUse() {
        RecordingCore core = new RecordingCore();
        Client client = core.client();
        Items items = client.items();
        client.close();
        client.close();
        assertEquals(List.of("41"), core.released);
        assertThrows(IllegalStateException.class, () -> items.get("vault", "item"));
        assertThrows(IllegalStateException.class, () -> items.files().read("v", "i",
                new com.onepassword.sdk.model.FileAttributes("file", "id", 10)));
    }

    @Test
    void expiredDesktopSessionRenewsOnceAndReleasesBothIds() {
        RecordingCore core = new RecordingCore();
        core.handler = request -> {
            if (core.invocations.size() == 1) {
                throw new DesktopSessionExpiredException("expired");
            }
            return "\"secret\"";
        };
        try (Client client = Client.builder().desktopAccount("account").core(core).build()) {
            assertEquals("secret", client.secrets().resolve("op://vault/item/password"));
            assertEquals(2, core.configurations.size());
            assertFalse(core.configurations.get(0).has("serviceAccountToken"));
            assertEquals(42, core.invocations.get(1).at("/invocation/clientId").asInt());
        }
        assertEquals(List.of("41", "42"), core.released);
    }

    @Test
    void sessionRenewalDoesNotLoop() {
        RecordingCore core = new RecordingCore();
        core.handler = request -> { throw new DesktopSessionExpiredException("expired"); };
        try (Client client = Client.builder().desktopAccount("account").core(core).build()) {
            assertThrows(DesktopSessionExpiredException.class, () -> client.secrets().resolve("op://v/i/f"));
            assertEquals(2, core.invocations.size());
            assertEquals(2, core.configurations.size());
        }
    }

    @Test
    void serviceAccountAndRateLimitErrorsAreNotRetried() {
        for (OnePasswordException error : List.of(new DesktopSessionExpiredException("expired"),
                new RateLimitExceededException("limited"), new OnePasswordException("failure"))) {
            RecordingCore core = new RecordingCore();
            core.handler = request -> { throw error; };
            try (Client client = core.client()) {
                assertSame(error, assertThrows(OnePasswordException.class,
                        () -> client.items().delete("vault", "item")));
                assertEquals(1, core.invocations.size());
                assertEquals(1, core.configurations.size());
            }
        }
    }

    @Test
    void closeWaitsForInFlightOperation() throws Exception {
        RecordingCore core = new RecordingCore();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);
        core.handler = request -> {
            entered.countDown();
            try {
                assertTrue(proceed.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
            return "\"secret\"";
        };
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Client client = core.client();
        try {
            Future<String> call = executor.submit(() -> client.secrets().resolve("op://v/i/f"));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            CountDownLatch closing = new CountDownLatch(1);
            Future<?> close = executor.submit(() -> { closing.countDown(); client.close(); });
            assertTrue(closing.await(5, TimeUnit.SECONDS));
            assertFalse(close.isDone());
            proceed.countDown();
            assertEquals("secret", call.get(5, TimeUnit.SECONDS));
            close.get(5, TimeUnit.SECONDS);
            assertEquals(List.of("41"), core.released);
        } finally {
            proceed.countDown();
            executor.shutdownNow();
            client.close();
        }
    }
}
