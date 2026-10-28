package com.onepassword.sdk;

import com.onepassword.sdk.internal.Json;
import com.onepassword.sdk.model.Item;
import com.onepassword.sdk.model.ItemShareAccountPolicy;

final class Fixtures {
    static final String ITEM = """
            {"id":"item-id","title":"Example","category":"Login","vaultId":"vault-id",
             "fields":[{"id":"password","title":"password","fieldType":"Concealed","value":"secret"}],
             "sections":[],"notes":"","tags":[],"websites":[],"version":3,"files":[],
             "createdAt":"2024-01-02T03:04:05.123Z","updatedAt":"2024-02-02T03:04:05Z"}
            """;
    static final String POLICY = """
            {"maxExpiry":"ThirtyDays","defaultExpiry":"SevenDays","allowedTypes":["Public","Authenticated"],
             "allowedRecipientTypes":["Email","Domain"],"files":{"allowed":true,"maxSize":2048}}
            """;
    static final String VAULT = """
            {"id":"vault-id","title":"Vault","description":"","vaultType":"userCreated",
             "activeItemCount":2,"contentVersion":5,"attributeVersion":1,"access":[]}
            """;
    static final String VAULT_OVERVIEW = """
            {"id":"vault-id","title":"Vault","description":"","vaultType":"userCreated",
             "activeItemCount":2,"contentVersion":5,"attributeVersion":1,
             "createdAt":"2024-01-01T00:00:00Z","updatedAt":"2024-02-01T00:00:00Z"}
            """;

    static Item item() { return Json.read(ITEM, Item.class); }
    static ItemShareAccountPolicy policy() { return Json.read(POLICY, ItemShareAccountPolicy.class); }

    private Fixtures() {}
}
