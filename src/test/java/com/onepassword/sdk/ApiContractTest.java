package com.onepassword.sdk;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.onepassword.sdk.internal.Json;
import com.onepassword.sdk.model.*;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

class ApiContractTest {
    private static final ItemCreateParams CREATE = ItemCreateParams.builder().category(ItemCategory.LOGIN)
            .vaultId("vault-id").title("Example").build();
    private static final FileAttributes FILE = new FileAttributes("data.bin", "file-id", 3);
    private static final FileCreateParams ATTACH = new FileCreateParams("data.bin",
            new byte[] {0, 127, (byte) 255}, "section-id", "field-id");
    private static final DocumentCreateParams DOCUMENT = new DocumentCreateParams("doc.bin", new byte[] {1, 2});

    private record Case(String method, String response, Set<String> keys, Consumer<Client> invoke) {}

    private static Case request(String method, String response, Consumer<Client> invoke, String... keys) {
        return new Case(method, response, Set.of(keys), invoke);
    }

    @TestFactory
    Stream<DynamicTest> everyOperationUsesItsNativeNameAndParameterKeys() {
        List<Case> cases = List.of(
                request("SecretsResolve", "\"secret\"", c -> assertEquals("secret", c.secrets().resolve("op://v/i/f")),
                        "secret_reference"),
                request("SecretsResolveAll", "{\"individualResponses\":{}}",
                        c -> c.secrets().resolveAll(List.of("op://v/i/f")), "secret_references"),
                request("ItemsCreate", Fixtures.ITEM, c -> c.items().create(CREATE), "params"),
                request("ItemsCreateAll", "{\"individualResponses\":[]}",
                        c -> c.items().createAll("vault-id", List.of(CREATE)), "vault_id", "params"),
                request("ItemsGet", Fixtures.ITEM, c -> c.items().get("vault-id", "item-id"), "vault_id", "item_id"),
                request("ItemsGetAll", "{\"individualResponses\":[]}",
                        c -> c.items().getAll("vault-id", List.of("item-id")), "vault_id", "item_ids"),
                request("ItemsPut", Fixtures.ITEM, c -> c.items().put(Fixtures.item()), "item"),
                request("ItemsDelete", "null", c -> c.items().delete("vault-id", "item-id"), "vault_id", "item_id"),
                request("ItemsDeleteAll", "{\"individualResponses\":{}}",
                        c -> c.items().deleteAll("vault-id", List.of("item-id")), "vault_id", "item_ids"),
                request("ItemsArchive", "null", c -> c.items().archive("vault-id", "item-id"), "vault_id", "item_id"),
                request("ItemsList", "[]", c -> c.items().list("vault-id", ItemListFilter.byState(true, false)),
                        "vault_id", "filters"),
                request("ItemsFilesAttach", Fixtures.ITEM, c -> c.items().files().attach(Fixtures.item(), ATTACH),
                        "item", "file_params"),
                request("ItemsFilesRead", "[0,127,255]", c -> assertArrayEquals(ATTACH.content(),
                        c.items().files().read("vault-id", "item-id", FILE)), "vault_id", "item_id", "attr"),
                request("ItemsFilesDelete", Fixtures.ITEM,
                        c -> c.items().files().delete(Fixtures.item(), "section-id", "field-id"), "item", "section_id", "field_id"),
                request("ItemsFilesReplaceDocument", Fixtures.ITEM,
                        c -> c.items().files().replaceDocument(Fixtures.item(), DOCUMENT), "item", "doc_params"),
                request("ItemsSharesGetAccountPolicy", Fixtures.POLICY,
                        c -> c.items().shares().getAccountPolicy("vault-id", "item-id"), "vault_id", "item_id"),
                request("ItemsSharesValidateRecipients", "[{\"type\":\"Email\",\"content\":{\"email\":\"a@example.com\"}}]",
                        c -> assertEquals(ValidRecipient.email("a@example.com"), c.items().shares()
                                .validateRecipients(Fixtures.policy(), List.of("a@example.com")).get(0)), "policy", "recipients"),
                request("ItemsSharesCreate", "\"https://share.example/link\"",
                        c -> c.items().shares().create(Fixtures.item(), Fixtures.policy(),
                                new ItemShareParams(List.of(ValidRecipient.email("a@example.com")),
                                        ItemShareDuration.ONE_DAY, true)), "item", "policy", "params"),
                request("VaultsCreate", Fixtures.VAULT, c -> c.vaults().create(new VaultCreateParams("Vault", null, true)), "params"),
                request("VaultsList", "[" + Fixtures.VAULT_OVERVIEW + "]",
                        c -> assertEquals("vault-id", c.vaults().list(new VaultListParams(true)).get(0).id()), "params"),
                request("VaultsGetOverview", Fixtures.VAULT_OVERVIEW, c -> c.vaults().getOverview("vault-id"), "vault_id"),
                request("VaultsGet", Fixtures.VAULT, c -> c.vaults().get("vault-id", new VaultGetParams(true)), "vault_id", "vault_params"),
                request("VaultsUpdate", Fixtures.VAULT, c -> c.vaults().update("vault-id", new VaultUpdateParams("Renamed", null)),
                        "vault_id", "params"),
                request("VaultsDelete", "null", c -> c.vaults().delete("vault-id"), "vault_id"),
                request("VaultsGrantGroupPermissions", "null", c -> c.vaults().grantGroupPermissions("vault-id",
                        List.of(new GroupAccess("group-id", VaultPermissions.READ_ITEMS))), "vault_id", "group_permissions_list"),
                request("VaultsUpdateGroupPermissions", "null", c -> c.vaults().updateGroupPermissions(
                        List.of(new GroupVaultAccess("vault-id", "group-id", VaultPermissions.READ_ITEMS))), "group_permissions_list"),
                request("VaultsRevokeGroupPermissions", "null", c -> c.vaults().revokeGroupPermissions("vault-id", "group-id"),
                        "vault_id", "group_id"),
                request("GroupsGet", "{\"id\":\"group-id\",\"title\":\"Group\",\"description\":\"\","
                                + "\"groupType\":\"userDefined\",\"state\":\"active\"}",
                        c -> c.groups().get("group-id", new GroupGetParams(true)), "group_id", "group_params"),
                request("EnvironmentsGetVariables", "{\"variables\":[{\"name\":\"KEY\",\"value\":\"secret\",\"masked\":true}]}",
                        c -> assertEquals("secret", c.environments().getVariables("environment-id").variables().get(0).value()),
                        "environment_id"));
        return cases.stream().map(test -> DynamicTest.dynamicTest(test.method(), () -> {
            RecordingCore core = new RecordingCore();
            core.handler = request -> test.response();
            try (Client client = core.client()) {
                test.invoke().accept(client);
            }
            JsonNode request = core.invocations.get(0);
            assertEquals(1, core.invocations.size());
            assertEquals(41, request.at("/invocation/clientId").asInt());
            assertEquals(test.method(), request.at("/invocation/parameters/name").asText());
            JsonNode parameters = request.at("/invocation/parameters/parameters");
            Set<String> keys = new HashSet<>();
            parameters.fieldNames().forEachRemaining(keys::add);
            assertEquals(test.keys(), keys);
            for (String key : List.of("vault_id", "item_id", "group_id", "section_id", "field_id", "environment_id")) {
                if (parameters.has(key)) {
                    assertEquals(key.replace('_', '-'), parameters.get(key).asText());
                }
            }
            if (test.method().equals("ItemsFilesAttach")) {
                assertEquals("[0,127,255]", parameters.at("/file_params/content").toString());
            }
            if (test.method().equals("ItemsList")) {
                assertEquals("ByState", parameters.at("/filters/0/type").asText());
                assertTrue(parameters.at("/filters/0/content/active").asBoolean());
            }
        }));
    }

    @Test
    void utilityInvocationsOmitClientId() {
        RecordingCore core = new RecordingCore();
        core.handler = request -> "{\"password\":\"1234\"}";
        var utilities = new SecretUtilities(core);
        assertEquals("1234", utilities.generatePassword(PasswordRecipe.pin(4)).password());
        assertFalse(core.invocations.get(0).path("invocation").has("clientId"));
        assertEquals(Json.tree("{\"recipe\":{\"type\":\"Pin\",\"parameters\":{\"length\":4}}}"),
                core.invocations.get(0).at("/invocation/parameters/parameters"));
    }

    @Test
    void defaultFiltersAndOptionalParametersUseTheNativeDefaults() {
        RecordingCore core = new RecordingCore();
        core.handler = request -> "[]";
        try (Client client = core.client()) {
            client.items().list("vault-id");
            assertEquals("[]", core.invocations.get(0).at("/invocation/parameters/parameters/filters").toString());
            client.vaults().list();
            assertTrue(core.invocations.get(1).at("/invocation/parameters/parameters/params").isMissingNode());
        }
    }
}
