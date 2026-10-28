import com.onepassword.sdk.Client;

/** The desktop app prompts the user to authorize this integration. */
public final class DesktopExample {
    private DesktopExample() {}

    public static void main(String[] args) {
        if (args.length != 2) {
            throw new IllegalArgumentException("Usage: DesktopExample <account-name> <op://vault/item/field>");
        }
        try (Client client = Client.builder().desktopAccount(args[0])
                .integration("Java desktop example", "1.0.0").build()) {
            String secret = client.secrets().resolve(args[1]);
            System.out.println("Secret resolved: " + !secret.isEmpty());
        }
    }
}
