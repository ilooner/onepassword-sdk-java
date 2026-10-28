import com.onepassword.sdk.Client;
import com.onepassword.sdk.model.ItemCategory;
import com.onepassword.sdk.model.ItemCreateParams;
import com.onepassword.sdk.model.ItemField;
import com.onepassword.sdk.model.ItemFieldType;
import java.util.List;

/** Run with a service account token, a secret reference, and optionally a vault ID. */
public final class ServiceAccountExample {
    private ServiceAccountExample() {}

    public static void main(String[] args) {
        if (args.length < 1) {
            throw new IllegalArgumentException("Usage: ServiceAccountExample <op://vault/item/field> [vault-id]");
        }
        try (Client client = Client.builder()
                .serviceAccountToken(System.getenv("OP_SERVICE_ACCOUNT_TOKEN"))
                .integration("Java SDK example", "1.0.0")
                .build()) {
            String password = client.secrets().resolve(args[0]);
            // Supply password to your database or other integration here; avoid logging it.
            System.out.println("Secret resolved: " + !password.isEmpty());

            if (args.length > 1) {
                var created = client.items().create(ItemCreateParams.builder()
                        .vaultId(args[1])
                        .category(ItemCategory.LOGIN)
                        .title("Java SDK example")
                        .fields(List.of(ItemField.builder()
                                .id("username").title("username").fieldType(ItemFieldType.TEXT)
                                .value("example-user").build()))
                        .build());
                System.out.println("Created item " + created.id());
                var updated = client.items().put(created.toBuilder().title("Updated Java SDK example").build());
                client.items().archive(updated.vaultId(), updated.id());
            }
        }
    }
}
