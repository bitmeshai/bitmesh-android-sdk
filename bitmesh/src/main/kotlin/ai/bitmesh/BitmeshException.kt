package ai.bitmesh

import org.json.JSONObject

/** Base class of everything the SDK throws, apart from argument checks. */
public open class BitmeshException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The request never got an HTTP response: offline, DNS, TLS, timeout. */
public class BitmeshNetworkException(message: String, cause: Throwable? = null) : BitmeshException(message, cause)

/**
 * [BitmeshOptions.appCheckTokenProvider] threw or returned no token, so the
 * request was not sent. Usually App Check isn't set up for the app in the
 * Firebase console.
 */
public class BitmeshAppCheckTokenException(message: String, cause: Throwable? = null) : BitmeshException(message, cause)

/**
 * A non-2xx response, from Bitmesh or, on the proxy, from the provider.
 *
 * [message] is the API's reason: its `message`, else its `error`.
 */
public class BitmeshApiException(
    public val statusCode: Int,
    /** The response body as received. */
    public val body: String,
    message: String,
    /** `Retry-After`, in seconds, on a `429` or `503`. */
    public val retryAfterSeconds: Long? = null,
    /** `X-Bitmesh-Request-Id`, when the API sent one. */
    public val requestId: String? = null,
) : BitmeshException(message) {

    /** `401` because the App Check token or debug token was missing or refused. */
    public val isAppCheckRejection: Boolean
        get() = statusCode == 401 && (
            message.orEmpty().contains("App Check") ||
                message.orEmpty().contains("X-Firebase-AppCheck") ||
                message.orEmpty().contains("debug token", ignoreCase = true)
            )

    /** The API couldn't verify App Check tokens at all. Worth retrying later. */
    public val isTemporaryAppCheckFailure: Boolean
        get() = isAppCheckRejection && message.orEmpty().contains("temporarily unavailable")

    internal companion object {
        fun from(status: Int, body: String, retryAfter: Long?, requestId: String?): BitmeshApiException {
            val reason = try {
                val json = JSONObject(body)
                json.optString("message").takeIf { it.isNotEmpty() }
                    ?: (json.opt("error") as? String)?.takeIf { it.isNotEmpty() }
                    ?: json.optJSONObject("error")?.optString("message")?.takeIf { it.isNotEmpty() }
            } catch (_: Exception) {
                null
            }
            return BitmeshApiException(
                statusCode = status,
                body = body,
                message = reason ?: "Bitmesh API request failed with status $status: ${body.take(300)}",
                retryAfterSeconds = retryAfter,
                requestId = requestId,
            )
        }
    }
}
