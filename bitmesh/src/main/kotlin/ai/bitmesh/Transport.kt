package ai.bitmesh

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

/** A response, with its body read unless it is an open stream. */
public class BitmeshResponse internal constructor(
    public val statusCode: Int,
    /** Header names in lower case. */
    public val headers: Map<String, String>,
    public val body: ByteArray,
) {
    /** `X-Bitmesh-Request-Id`, on proxy requests and streamed chat. */
    public val requestId: String? get() = headers["x-bitmesh-request-id"]

    public val text: String get() = body.toString(Charsets.UTF_8)

    /** The body parsed as a JSON object. */
    public fun json(): JSONObject = try {
        JSONObject(text)
    } catch (e: Exception) {
        throw BitmeshException("The response is not a JSON object: ${text.take(200)}", e)
    }
}

internal class Request(
    val method: String,
    val url: URL,
    val body: ByteArray? = null,
    val contentType: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val authenticated: Boolean = true,
)

private class Exchange(val response: BitmeshResponse, val connection: HttpURLConnection?)

/** Sends requests with the key and App Check token, and retries. */
internal class Transport(private val apiKey: String, private val options: BitmeshOptions) {
    private val base = options.baseUrl.trimEnd('/')

    init {
        require(base.startsWith("https://") || base.startsWith("http://")) {
            "Invalid baseUrl \"${options.baseUrl}\". Expected an absolute URL such as \"https://api.bitmesh.ai\"."
        }
    }

    /** `{baseUrl}/v2/{path}`. */
    fun apiUrl(path: String, query: Map<String, String> = emptyMap()): URL =
        url("$API_VERSION/${path.trimStart('/')}", query)

    /** A path relative to the base URL, without a version, or an absolute URL. */
    fun publicUrl(pathOrUrl: String): URL =
        if (pathOrUrl.startsWith("http://") || pathOrUrl.startsWith("https://")) URL(pathOrUrl) else url(pathOrUrl.trimStart('/'))

    private fun url(path: String, query: Map<String, String> = emptyMap()): URL {
        val q = if (query.isEmpty()) "" else query.entries.joinToString("&", prefix = if ('?' in path) "&" else "?") {
            encode(it.key) + "=" + encode(it.value)
        }
        return URL("$base/$path$q")
    }

    suspend fun json(method: String, path: String, payload: Map<String, Any?>? = null, query: Map<String, String> = emptyMap()): JSONObject =
        send(
            Request(
                method,
                apiUrl(path, query),
                body = payload?.let { toJson(it).toString().toByteArray() },
                contentType = payload?.let { "application/json" },
            ),
        ).json()

    suspend fun multipart(path: String, fields: Map<String, Any?>, files: Map<String, BitmeshFile>): JSONObject {
        val boundary = "bitmesh-" + UUID.randomUUID()
        val body = withContext(Dispatchers.IO) { multipartBody(boundary, fields, files) }
        return send(Request("POST", apiUrl(path), body, "multipart/form-data; boundary=$boundary")).json()
    }

    /** One request with retries. Non-2xx answers throw [BitmeshApiException]. */
    suspend fun send(request: Request): BitmeshResponse = execute(request, stream = false).response

    /** A request whose 2xx reply is read as server-sent events, as they arrive. */
    fun events(request: Request): Flow<ServerSentEvent> = callbackFlow {
        val exchange = execute(request, stream = true)
        val connection = exchange.connection!!
        launch(Dispatchers.IO) {
            try {
                val parser = ServerSentEventParser()
                connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        parser.feed(line)?.let { send(it) }
                    }
                }
                parser.finish()?.let { send(it) }
                close()
            } catch (e: IOException) {
                close(BitmeshNetworkException("The stream from ${request.url.host} was cut off.", e))
            } catch (e: Throwable) {
                close(e)
            }
        }
        // Closing the connection unblocks the read when the collector stops early.
        awaitClose { connection.disconnect() }
    }

    private suspend fun execute(request: Request, stream: Boolean): Exchange {
        var attempt = 0
        while (true) {
            val auth = if (request.authenticated) authHeaders() else emptyMap()
            val exchange = try {
                attemptOnce(request, auth, stream)
            } catch (e: IOException) {
                if (request.method == "GET" && attempt < options.maxRetries) {
                    delay(backoff(attempt++, null))
                    continue
                }
                throw BitmeshNetworkException("Could not reach the Bitmesh API at ${request.url.host}: ${e.message}", e)
            }

            val response = exchange.response
            if (response.statusCode in 200..299) return exchange

            val retryAfter = response.headers["retry-after"]?.trim()?.toLongOrNull()
            val retryable = response.statusCode == 429 ||
                (request.method == "GET" && response.statusCode in setOf(500, 502, 503, 504))
            if (retryable && attempt < options.maxRetries) {
                delay(backoff(attempt++, retryAfter))
                continue
            }
            throw BitmeshApiException.from(response.statusCode, response.text, retryAfter, response.requestId)
        }
    }

    /** One HTTP exchange, on the IO dispatcher. Cancelling the caller closes the connection. */
    private suspend fun attemptOnce(request: Request, auth: Map<String, String>, stream: Boolean): Exchange = coroutineScope {
        val connection = request.url.openConnection() as HttpURLConnection
        val done = AtomicBoolean(false)
        val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                if (!done.get()) connection.disconnect()
            }
        }
        try {
            withContext(Dispatchers.IO) {
                connection.requestMethod = request.method
                connection.connectTimeout = options.connectTimeoutMillis
                connection.readTimeout = options.readTimeoutMillis
                connection.instanceFollowRedirects = false
                connection.useCaches = false

                val headers = buildMap {
                    put("Accept", if (stream) "text/event-stream" else "application/json")
                    putAll(options.defaultHeaders)
                    options.userAgent?.let { put("User-Agent", it) }
                    request.contentType?.let { put("Content-Type", it) }
                    putAll(request.headers)
                    putAll(auth)
                }
                headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }

                request.body?.let { body ->
                    connection.doOutput = true
                    connection.setFixedLengthStreamingMode(body.size)
                    connection.outputStream.use { it.write(body) }
                }

                val status = connection.responseCode
                val responseHeaders = connection.headerFields
                    .filterKeys { it != null }
                    .mapKeys { it.key.lowercase() }
                    .mapValues { it.value.joinToString(", ") }
                val success = status in 200..299
                val body = if (stream && success) ByteArray(0) else readAll(if (success) connection.inputStream else connection.errorStream)
                done.set(true)
                Exchange(BitmeshResponse(status, responseHeaders, body), if (stream && success) connection else null)
            }
        } finally {
            watcher.cancel()
        }
    }

    /**
     * The headers of an authenticated request: defaults, `User-Agent`, the key
     * and the App Check or debug token. For the real-time transcription
     * WebSocket, which this transport doesn't send itself.
     */
    suspend fun authenticatedHeaders(): Map<String, String> = buildMap {
        putAll(options.defaultHeaders)
        options.userAgent?.let { put("User-Agent", it) }
        putAll(authHeaders())
    }

    private suspend fun authHeaders(): Map<String, String> {
        val headers = mutableMapOf("Authorization" to "Bearer $apiKey")
        val debugToken = options.debugToken
        if (debugToken != null) {
            headers[DEBUG_TOKEN_HEADER] = debugToken
            return headers
        }
        val provider = options.appCheckTokenProvider ?: return headers
        val token = try {
            provider()
        } catch (e: Exception) {
            throw BitmeshAppCheckTokenException(
                "Could not get a Firebase App Check token. Check that App Check is set up for this app in the Firebase console.",
                e,
            )
        }
        if (token.isNullOrBlank()) {
            throw BitmeshAppCheckTokenException("The App Check token provider returned no token. Check that App Check is installed before the first request.")
        }
        headers[APP_CHECK_HEADER] = token
        return headers
    }

    private fun backoff(attempt: Int, retryAfterSeconds: Long?): Long =
        retryAfterSeconds?.let { min(it, 60) * 1000 } ?: (500L shl attempt)

    internal companion object {
        const val API_VERSION = "v2"
        const val APP_CHECK_HEADER = "X-Firebase-AppCheck"
        const val DEBUG_TOKEN_HEADER = "X-Bitmesh-Debug-Token"

        fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

        private fun readAll(stream: InputStream?): ByteArray = stream?.use { it.readBytes() } ?: ByteArray(0)

        /** Maps and lists to org.json, at any depth. */
        fun toJson(value: Map<String, Any?>): JSONObject = JSONObject().apply {
            value.forEach { (key, item) -> put(key, wrap(item)) }
        }

        private fun wrap(value: Any?): Any? = when (value) {
            null -> JSONObject.NULL
            is Map<*, *> -> JSONObject().apply { value.forEach { (k, v) -> put(k.toString(), wrap(v)) } }
            is Iterable<*> -> JSONArray().apply { value.forEach { put(wrap(it)) } }
            is Array<*> -> JSONArray().apply { value.forEach { put(wrap(it)) } }
            else -> value
        }

        /**
         * Multipart fields travel as text: booleans as `1`/`0`, numbers as
         * their string form, nulls left out, nested maps as `parent[child]`
         * and lists as `name[0]`, `name[1]`.
         */
        fun flatten(fields: Map<String, Any?>, prefix: String = ""): List<Pair<String, String>> =
            fields.toSortedMap().flatMap { (key, value) -> flattenValue(if (prefix.isEmpty()) key else "$prefix[$key]", value) }

        private fun flattenValue(name: String, value: Any?): List<Pair<String, String>> = when (value) {
            null, JSONObject.NULL -> emptyList()
            is Boolean -> listOf(name to if (value) "1" else "0")
            is Map<*, *> -> flatten(value.entries.associate { it.key.toString() to it.value }, name)
            is Iterable<*> -> value.flatMapIndexed { i, item -> flattenValue("$name[$i]", item) }
            else -> listOf(name to value.toString())
        }

        fun multipartBody(boundary: String, fields: Map<String, Any?>, files: Map<String, BitmeshFile>): ByteArray {
            val out = ByteArrayOutputStream()
            fun write(text: String) = out.write(text.toByteArray())
            for ((name, value) in flatten(fields)) {
                write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n")
            }
            for ((name, file) in files) {
                write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"; filename=\"${file.filename.replace("\"", "")}\"\r\n")
                write("Content-Type: ${file.contentType}\r\n\r\n")
                out.write(file.bytes())
                write("\r\n")
            }
            write("--$boundary--\r\n")
            return out.toByteArray()
        }
    }
}
