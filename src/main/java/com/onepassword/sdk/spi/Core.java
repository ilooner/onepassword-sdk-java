package com.onepassword.sdk.spi;

/**
 * JSON transport to the 1Password core. Implementations must be thread safe and
 * translate core errors into {@link com.onepassword.sdk.OnePasswordException}.
 * A core can be shared by multiple clients; closing a client releases only its ID.
 */
public interface Core {
    /** Authenticates and returns a JSON unsigned 64-bit client ID. */
    String initClient(String configuration);

    /** Executes an authenticated operation and returns its JSON result. */
    String invoke(String invocation);

    /** Executes a stateless utility operation and returns its JSON result. */
    String invokeSync(String invocation);

    /** Releases the client identified by a JSON unsigned 64-bit integer. */
    void releaseClient(String clientId);
}
