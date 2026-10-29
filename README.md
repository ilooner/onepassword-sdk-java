# 1Password SDK for Java

A community Java SDK using the same native core as the official
[Python](https://github.com/1Password/onepassword-sdk-python),
[Go](https://github.com/1Password/onepassword-sdk-go), and
[JavaScript](https://github.com/1Password/onepassword-sdk-js) SDKs.
This project is not an official 1Password release.

Requires **Java 17 or later**. The build uses **Groovy Gradle**, with a checked-in
Gradle wrapper. APIs are synchronous, with immutable records, builders, typed
errors, and deterministic cleanup through `AutoCloseable`.

## Build and installation

```sh
./gradlew build                       # Java tests, compiled examples, sources, Javadoc, JAR
./gradlew nativeTest                  # Download verified native core; run offline ABI tests
./gradlew packagedNativeTest          # Test loading from the platform JAR
./gradlew nativeJar                   # Package native core for the current platform
```

The Java JAR and native JAR are separate. The native download is pinned to an
upstream commit and verified with SHA-256. No library is downloaded at runtime.
The regular `build` task does not download the 1Password core.

To use this checkout in another local Gradle project:

```sh
./gradlew publishToMavenLocal
```

```groovy
repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation 'com.ilooner:onepassword-sdk-java:0.1.0-SNAPSHOT'
    // Select the classifier matching your deployment's operating system and CPU.
    runtimeOnly 'com.ilooner:onepassword-sdk-java:0.1.0-SNAPSHOT:native-macos-aarch64'
}
```

The Maven coordinates are `com.ilooner:onepassword-sdk-java` (group `com.ilooner`,
artifact `onepassword-sdk-java`). These coordinates are for local publication;
the release workflows also publish them to GitHub Packages. No Maven Central
release is claimed.
`publishToMavenLocal` includes the current platform's native JAR. To package or
publish another platform, pass `-PnativePlatform=linux-x86_64`, for example.

| Platform | Native classifier |
| --- | --- |
| macOS, Apple Silicon | `native-macos-aarch64` |
| macOS, Intel | `native-macos-x86_64` |
| Linux, ARM64 | `native-linux-aarch64` |
| Linux, x86-64 | `native-linux-x86_64` |
| Windows, x86-64 | `native-windows-x86_64` |

Linux requires a glibc-compatible environment; musl/Alpine and 32-bit JVMs are not
supported by these artifacts. Windows ARM64 has no pinned upstream native binary.

Alternatively, run `./gradlew downloadNative` and point the JVM at the resulting
library with `-Donepassword.native.path=/absolute/path/to/library`, or set
`ONEPASSWORD_NATIVE_LIBRARY`. This takes precedence over a platform JAR.

## Authenticate and resolve a secret

Provision `OP_SERVICE_ACCOUNT_TOKEN` with a
[1Password service account](https://developer.1password.com/docs/service-accounts/).
Pass it explicitly; the client does not implicitly read credentials.

```java
import com.onepassword.sdk.Client;

try (Client client = Client.builder()
        .serviceAccountToken(System.getenv("OP_SERVICE_ACCOUNT_TOKEN"))
        .integration("My integration", "1.0.0")
        .build()) {
    String password = client.secrets().resolve("op://vault/item/password");
    // Supply password to your integration. Avoid logging secrets.
}
```

For desktop authentication, enable **Settings > Developer > Integrate with other
apps** in the 1Password desktop application, then use:

```java
try (Client client = Client.builder()
        .desktopAccount("My account")
        .integration("My desktop integration", "1.0.0")
        .build()) {
    var vaults = client.vaults().list();
}
```

The account name is the name shown in the desktop app. Desktop authentication
loads the app's IPC library from standard installation locations. Override the
location with `-Donepassword.desktop.library.path=/absolute/path/to/library`.
Authenticated desktop operations do not require the separate native core JAR;
offline password generation and reference validation do.

Specify exactly one authentication method. Construction authenticates immediately.

## Create and update items

```java
import com.onepassword.sdk.model.ItemCategory;
import com.onepassword.sdk.model.ItemCreateParams;
import com.onepassword.sdk.model.ItemField;
import com.onepassword.sdk.model.ItemFieldType;
import java.util.List;

var item = client.items().create(ItemCreateParams.builder()
        .category(ItemCategory.LOGIN)
        .vaultId("vault-id")
        .title("Example login")
        .fields(List.of(
                ItemField.builder().id("username").title("username")
                        .fieldType(ItemFieldType.TEXT).value("alice").build(),
                ItemField.builder().id("password").title("password")
                        .fieldType(ItemFieldType.CONCEALED).value(password).build()))
        .build());

var updated = client.items().put(item.toBuilder().title("Renamed login").build());
```

`toBuilder()` preserves the item's ID and version for updates. Lists and maps are
defensively copied; file contents are copied on construction and access. Optional
properties may be `null`. Wire timestamps use `Instant`; unsigned 32-bit counters
and permission masks use Java `long`.

## Available operations

| API | Operations |
| --- | --- |
| `client.secrets()` | `resolve`, `resolveAll` |
| `client.items()` | `create`, `createAll`, `get`, `getAll`, `put`, `delete`, `deleteAll`, `archive`, `list` |
| `client.items().files()` | `attach`, `read`, `delete`, `replaceDocument` |
| `client.items().shares()` | `getAccountPolicy`, `validateRecipients`, `create` |
| `client.vaults()` | `create`, `list`, `getOverview`, `get`, `update`, `delete`, `grantGroupPermissions`, `updateGroupPermissions`, `revokeGroupPermissions` |
| `client.groups()` | `get` |
| `client.environments()` | `getVariables` |
| `Secrets` static methods | `validateSecretReference`, `generatePassword` |

For filtered listing, use `ItemListFilter.byState(true, false)`. Combine group
permissions with bitwise OR, such as
`VaultPermissions.READ_ITEMS | VaultPermissions.UPDATE_ITEMS`.

Batch results expose each operation's `content()` or typed `error()`, and an
`isSuccess()` helper. Check each result; a batch can partially succeed. A successful
batch deletion has no content. Secret-resolution and deletion results are keyed
by reference or ID; item-get and item-create results are lists.

File attachment and document requests accept `byte[]`; `files().read(...)` returns
`byte[]`. The binding sends unsigned JSON byte arrays as required by the core.
For sharing, obtain the account policy, validate recipients, then pass those
`ValidRecipient` values in `ItemShareParams` to `shares().create(...)`.

## Offline utilities

```java
import com.onepassword.sdk.Secrets;
import com.onepassword.sdk.model.PasswordRecipe;
import com.onepassword.sdk.model.SeparatorType;
import com.onepassword.sdk.model.WordListType;

Secrets.validateSecretReference("op://vault/item/password");
String random = Secrets.generatePassword(PasswordRecipe.random(32, true, true)).password();
String pin = Secrets.generatePassword(PasswordRecipe.pin(6)).password();
String memorable = Secrets.generatePassword(PasswordRecipe.memorable(
        4, SeparatorType.HYPHENS, false, WordListType.FULL_WORDS)).password();
```

These use the core's secure password generator and parser without authentication
or network access. Invalid references throw `OnePasswordException`.

## Errors, threading, and lifecycle

- Use try-with-resources. `close()` is idempotent, waits for an in-flight call,
  and releases native client state. A Cleaner provides a fallback for abandoned
  clients; deterministic cleanup should use `close()`.
- Calls on a client are serialized. Different clients can execute concurrently.
  To integrate with asynchronous code, call the SDK on an application-managed
  executor. Interrupting a native asynchronous call cancels its Rust future and
  restores the Java thread's interrupt flag. Cancellation does not roll back an
  operation already accepted by the server.
- `OnePasswordException` is unchecked. `RateLimitExceededException` and
  `DesktopSessionExpiredException` identify the corresponding core errors.
  `getErrorName()` retains other named errors. Invalid Java arguments use
  `IllegalArgumentException` or `NullPointerException`; use after close throws
  `IllegalStateException`.
- An expired desktop session reauthenticates and retries the call once. Other
  failures are returned without automatic retries.
- Requests are limited to 50 MiB of serialized UTF-8, including JSON overhead.
  Models redact their `toString()` output; the SDK does not log requests or secrets.

## Compatibility and development

The native baseline is Python SDK **0.4.2**, build `0040201`, with UniFFI contract
25 and verified function checksums. The Java implementation covers all 29
authenticated operations in the referenced Go API and its two offline utilities.
The newer JavaScript-only OIDC/workload-identity callback is **not supported** by
this native baseline. Account permissions and backend capabilities still govern
which operations are available to an authenticated client.

Unit tests use an injectable `com.onepassword.sdk.spi.Core` transport to check API
requests, model encoding, batch failures, cleanup, and session renewal. Native
tests exercise the actual library without credentials, including concurrent Rust
futures and locally rejected tokens. They do not validate live account operations
or desktop authorization prompts. CI is configured for Java 17 and 21 on Linux,
macOS, and Windows.

The model and service sources are checked in. To regenerate them:

```sh
python3 tools/generate_models.py
python3 tools/generate_api.py
```

Their source schema is `tools/sdk-schema.json`; update it alongside the pinned
native ABI when upgrading upstream. The build does not require Python.
Compilable examples are in [`examples/`](examples/), and generated API docs are in
`build/docs/javadoc/` after `./gradlew build`.

## Publishing releases

The GitHub workflows are:

- [`ci.yml`](.github/workflows/ci.yml): builds and tests Java 17 and 21 on Linux,
  macOS, and Windows. It can also be called by the release workflow.
- [`release.yml`](.github/workflows/release.yml): runs when a GitHub Release is
  published, or manually through **Actions > Release > Run workflow**. It validates
  the version tag, pins its commit, and requires the complete CI matrix to pass.
- [`publish-artifacts.yml`](.github/workflows/publish-artifacts.yml): called by
  the release workflow to build and verify the publication bundle, publish Maven
  artifacts to GitHub Packages, and attach files to the existing GitHub Release.

To publish, tag a commit containing these workflows with a version such as
`v1.2.3` or `v1.2.3-rc.1`, push the tag, and publish its GitHub Release. The tag
determines the Maven/JAR version and `Client.VERSION`; the development default
remains `0.1.0-SNAPSHOT`. Snapshot tags and malformed version tags are rejected.

Each release includes:

- The Java binary JAR, `-sources.jar`, and `-javadoc.jar`.
- Native classifier JARs for all five supported operating-system/CPU combinations.
- Maven POM and Gradle module metadata, plus a `SHA256SUMS` file covering the bundle.

Native libraries are packaged from the pinned upstream artifacts and their hashes
are checked again before publication. Native libraries are not recompiled by this
project. GitHub's source archives are available alongside the attached source JAR.

For a dry run, manually select an existing version tag and leave **publish** set
to **false** (the default). This runs CI and makes the complete bundle available
as a workflow artifact for 30 days, without uploading to the release or package
registry. To publish manually, select **true**; a published GitHub Release for that
tag must already exist. Published Maven versions should be treated as immutable:
use a new version tag for subsequent changes.

Publishing uses the workflow's `GITHUB_TOKEN` with `contents: write` and
`packages: write`; no additional repository secrets or personal credentials are
stored in the project. Repository/organization policy must permit these token
permissions. The registry URL is derived from `GITHUB_REPOSITORY`, so no owner
identity is hardcoded in the build. This follows the
[GitHub Packages Gradle publishing setup](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-gradle-registry).

To consume a published version, add the repository below alongside `mavenCentral()`
and use the `com.ilooner:onepassword-sdk-java:<version>` coordinates. Replace
`OWNER/REPOSITORY` with the repository hosting the package. GitHub Packages requires
authentication for downloads; use a token with package read access. Keep it in the
environment or your local Gradle credentials, outside version control.

```groovy
repositories {
    mavenCentral()
    maven {
        url = uri('https://maven.pkg.github.com/OWNER/REPOSITORY')
        credentials {
            username = System.getenv('GITHUB_ACTOR')
            password = System.getenv('GITHUB_TOKEN')
        }
    }
}
```

You can exercise the complete publication locally without uploading anything:

```sh
./gradlew releaseArtifacts publishMavenJavaPublicationToReleaseStagingRepository \
    -PreleaseVersion=1.2.3 -PallNativePlatforms=true
python3 .github/scripts/release.py verify 1.2.3
```

The downloadable bundle is staged under `build/release/`; a complete Maven
repository is staged under `build/release-repository/`. The release workflow uses
the explicit `publishMavenJavaPublicationToGitHubPackagesRepository` task for
remote publication. Ordinary builds and local staging do not publish remotely.

Licensed under MIT. See [LICENSE](LICENSE) and
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for upstream attribution.
