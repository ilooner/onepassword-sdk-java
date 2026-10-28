import com.onepassword.sdk.Secrets;
import com.onepassword.sdk.model.PasswordRecipe;

/** Uses the native core without contacting 1Password or needing an account. */
public final class OfflineExample {
    private OfflineExample() {}

    public static void main(String[] args) {
        Secrets.validateSecretReference("op://vault/item/password");
        String password = Secrets.generatePassword(PasswordRecipe.random(32, true, true)).password();
        System.out.println("Generated a password of length " + password.length());
    }
}
