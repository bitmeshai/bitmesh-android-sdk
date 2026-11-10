package ai.bitmesh

/**
 * Returns a current Firebase App Check token, or `null` if none is available.
 *
 * Called before every request attempt, retries included. Return the Firebase
 * SDK's cached token; it refreshes itself:
 *
 * ```
 * appCheckTokenProvider = {
 *     FirebaseAppCheck.getInstance().getAppCheckToken(false).await().token
 * }
 * ```
 */
public typealias AppCheckTokenProvider = suspend () -> String?

/** Settings shared by every request a [BitmeshClient] sends. */
public class BitmeshOptions(
    /** API root without a version. The client calls `{baseUrl}/v2/...`. */
    public val baseUrl: String = DEFAULT_BASE_URL,
    public val connectTimeoutMillis: Int = 15_000,
    /**
     * Longest wait for the response, or, on a streamed reply, for its next
     * piece. Raise it for long non-streamed chat replies.
     */
    public val readTimeoutMillis: Int = 120_000,
    /**
     * Extra attempts after a `429` (any request) or a `5xx` or network error
     * (`GET` only), honouring `Retry-After`. Writes are never retried on a
     * `5xx`: the job may have been created and billed already.
     */
    public val maxRetries: Int = 2,
    /**
     * Supplies the token sent as `X-Firebase-AppCheck`. Required for mobile
     * keys: the API refuses every request on one without a valid token. Leave
     * `null` for server keys.
     */
    public val appCheckTokenProvider: AppCheckTokenProvider? = null,
    /**
     * A Bitmesh debug token (`bmdbg_...`), sent as `X-Bitmesh-Debug-Token` in
     * place of App Check while testing on an emulator. When set,
     * [appCheckTokenProvider] is not called. Never set it in a release build:
     * `debugToken = if (BuildConfig.DEBUG) token else null`.
     */
    public val debugToken: String? = null,
    public val userAgent: String? = null,
    /** Sent on every request. Can't replace the key or the App Check token. */
    public val defaultHeaders: Map<String, String> = emptyMap(),
) {
    public companion object {
        public const val DEFAULT_BASE_URL: String = "https://api.bitmesh.ai"
    }
}
