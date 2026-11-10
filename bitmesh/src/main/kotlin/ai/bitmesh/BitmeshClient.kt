package ai.bitmesh

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.takeWhile
import org.json.JSONObject

/**
 * Client for the Bitmesh AI API v2: chat (streaming included), image, video,
 * transcription, tools and the provider proxy.
 *
 * Every request carries the key as `Authorization: Bearer`, plus the App Check
 * token (or a debug token) on mobile keys. Create one client and reuse it; it
 * is safe to share between coroutines.
 *
 * ```
 * val client = BitmeshClient("bmk_...")
 * val reply = client.chat(listOf(ChatMessage.user("Hello")))
 * println(reply.text)
 * ```
 *
 * Request bodies are the ones described in the API reference, as maps (nested
 * maps and lists become JSON). Replies are `org.json.JSONObject`s.
 *
 * @param apiKey a v2 key from the dashboard, starting `bmk_`, hosted or proxy.
 */
public class BitmeshClient(apiKey: String, public val options: BitmeshOptions = BitmeshOptions()) {
    private val transport: Transport

    init {
        val key = apiKey.trim()
        require(key.isNotEmpty()) { "The Bitmesh API key is empty." }
        require(key.startsWith("bmk_")) {
            "Bitmesh API v2 keys start with \"bmk_\". A v1 consumer key and secret is not supported by this SDK; create a v2 key in the dashboard."
        }
        transport = Transport(key, options)
    }

    // Account

    /** `GET /v2/balance`: the account's credit balance. */
    public suspend fun balance(): Double = transport.json("GET", "balance").optDouble("balance", 0.0)

    /** `GET /v2/test`: returns when the key, and on a mobile key the App Check or debug token, is accepted. */
    public suspend fun verify() {
        transport.json("GET", "test")
    }

    // Chat

    /** `POST /v2/chat` with a full body (`model`, `messages`, `max_tokens`, ...). */
    public suspend fun chat(payload: Map<String, Any?>): JSONObject = transport.json("POST", "chat", payload)

    /** `POST /v2/chat` from plain-text messages. [extra] adds body fields such as `max_tokens`. */
    public suspend fun chat(
        messages: List<ChatMessage>,
        model: String = DEFAULT_CHAT_MODEL,
        extra: Map<String, Any?> = emptyMap(),
    ): ChatResponse = ChatResponse(chat(chatPayload(messages, model, extra)))

    /**
     * `POST /v2/chat` with `"stream": true`: each chunk of the reply as it is
     * written, in OpenAI's format. The new text is in `choices[0].delta.content`;
     * the last chunk carries `usage`.
     *
     * The request is billed once, when the stream ends. Cancel the collecting
     * coroutine to stop generating: you pay only for what was written.
     */
    public fun chatStream(payload: Map<String, Any?>): Flow<JSONObject> =
        transport.events(Request("POST", transport.apiUrl("chat"), encodeBody(payload + ("stream" to true)), "application/json"))
            .takeWhile { !it.isDone }
            .map { event ->
                val json = event.json()
                if (json.has("error")) {
                    // The provider failed after the reply had started.
                    throw BitmeshApiException.from(502, event.data, null, null)
                }
                json
            }

    /**
     * Streams the reply to plain-text messages:
     *
     * ```
     * client.streamChat(listOf(ChatMessage.user("Write a haiku"))).collect { reply += it.text }
     * ```
     */
    public fun streamChat(
        messages: List<ChatMessage>,
        model: String = DEFAULT_CHAT_MODEL,
        extra: Map<String, Any?> = emptyMap(),
    ): Flow<ChatChunk> = chatStream(chatPayload(messages, model, extra)).map { ChatChunk(it) }

    // Image and video

    /** `POST /v2/image`. */
    public suspend fun image(payload: Map<String, Any?>): JSONObject = transport.json("POST", "image", payload)

    /** `POST /v2/video`. Returns the job; poll it with [getVideo]. */
    public suspend fun video(payload: Map<String, Any?>): JSONObject = transport.json("POST", "video", payload)

    /** `GET /v2/video/{id}`. */
    public suspend fun getVideo(id: String, query: Map<String, String> = emptyMap()): JSONObject =
        transport.json("GET", "video/${segment(id, "Video job id")}", query = query)

    // Transcription

    /** `POST /v2/transcribe-recorded`: uploads [audio]. Returns the job; poll it with [getTranscribeRecorded]. */
    public suspend fun transcribeFile(audio: BitmeshFile, fields: Map<String, Any?> = emptyMap()): JSONObject =
        transport.multipart("transcribe-recorded", fields, mapOf("audio" to audio))

    /** `GET /v2/transcribe-recorded/{id}`. */
    public suspend fun getTranscribeRecorded(id: String, query: Map<String, String> = emptyMap()): JSONObject =
        transport.json("GET", "transcribe-recorded/${segment(id, "Transcription job id")}", query = query)

    // Tools

    /** `POST /v2/tools/general/background-removal`. */
    public suspend fun toolsGeneralBackgroundRemoval(image: BitmeshFile, fields: Map<String, Any?> = emptyMap()): JSONObject =
        transport.multipart("tools/general/background-removal", fields, mapOf("image" to image))

    /** `POST /v2/tools/portrait/try-on-clothes`. [files] maps field names (`person_image`, `top_garment`, ...) to uploads. */
    public suspend fun toolsPortraitTryOnClothes(files: Map<String, BitmeshFile>, fields: Map<String, Any?> = emptyMap()): JSONObject =
        transport.multipart("tools/portrait/try-on-clothes", fields, files)

    /** `POST /v2/tools/query-async-task-result`: poll a try-on task. */
    public suspend fun toolsQueryAsyncTaskResult(taskId: String): JSONObject {
        require(taskId.isNotBlank()) { "Task id is required." }
        return transport.json("POST", "tools/query-async-task-result", mapOf("task_id" to taskId.trim()))
    }

    /**
     * `POST /v2/tools/video/image-animate`: image to video. Body: `model`,
     * `image` (`mapOf("bytes_base64_encoded" to ...)`), `seconds` (1 to 10), and
     * optional `prompt`, `aspect_ratio`, `callback_url`, `test`. Poll with [getImageAnimate].
     */
    public suspend fun imageAnimate(payload: Map<String, Any?>): JSONObject =
        transport.json("POST", "tools/video/image-animate", payload)

    /** `GET /v2/tools/video/image-animate/{task_id}`. */
    public suspend fun getImageAnimate(taskId: String, query: Map<String, String> = emptyMap()): JSONObject =
        transport.json("GET", "tools/video/image-animate/${segment(taskId, "Image-animate task id")}", query = query)

    /**
     * Downloads a result: an image link (`/imgrslt/...`), a tool result, or a
     * link on the provider's storage. Result links are public, so neither the
     * key nor the App Check token is sent.
     */
    public suspend fun fetchResult(urlOrPath: String): ByteArray {
        require(urlOrPath.isNotBlank()) { "Result link is required." }
        return transport.send(Request("GET", transport.publicUrl(urlOrPath.trim()), authenticated = false)).body
    }

    // Proxy

    /**
     * A request through the provider proxy, `/v2/proxy/{provider}/{path}`, with
     * a proxy key. Send the provider's own request minus its API key header;
     * Bitmesh adds the stored provider key. [headers] passes provider headers
     * that select options, such as `anthropic-version`.
     *
     * [method] is `GET`, `POST`, `PUT` or `DELETE`. A non-2xx answer throws
     * [BitmeshApiException] with the body, from Bitmesh or the provider.
     */
    public suspend fun proxy(
        provider: String,
        path: String,
        method: String = "POST",
        body: Map<String, Any?>? = null,
        query: Map<String, String> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
    ): BitmeshResponse {
        val verb = method.uppercase()
        require(verb in setOf("GET", "POST", "PUT", "DELETE")) { "Unsupported method $method. Use GET, POST, PUT or DELETE." }
        return transport.send(
            Request(verb, transport.apiUrl(proxyPath(provider, path), query), body?.let(::encodeBody), body?.let { "application/json" }, headers),
        )
    }

    /**
     * A streamed request through the proxy: sends `"stream": true` and returns
     * the provider's server-sent events as they arrive. OpenAI-style streams
     * end with an event whose [ServerSentEvent.isDone] is true; Anthropic names
     * each event in [ServerSentEvent.event].
     */
    public fun proxyStream(
        provider: String,
        path: String,
        body: Map<String, Any?>,
        headers: Map<String, String> = emptyMap(),
    ): Flow<ServerSentEvent> = transport.events(
        Request("POST", transport.apiUrl(proxyPath(provider, path)), encodeBody(body + ("stream" to true)), "application/json", headers),
    )

    private fun proxyPath(provider: String, path: String): String {
        val name = provider.trim().lowercase()
        require(Regex("^[a-z0-9-]+$").matches(name)) {
            "Invalid provider \"$provider\". Use the provider's name as in the docs, e.g. \"openai\", \"anthropic\" or \"together\"."
        }
        return "proxy/$name/${path.trim().trimStart('/')}"
    }

    private fun encodeBody(payload: Map<String, Any?>): ByteArray = Transport.toJson(payload).toString().toByteArray()

    private fun segment(id: String, what: String): String {
        require(id.isNotBlank()) { "$what is required." }
        return Transport.encode(id.trim())
    }

    private fun chatPayload(messages: List<ChatMessage>, model: String, extra: Map<String, Any?>): Map<String, Any?> =
        mapOf(
            "model" to model,
            "messages" to messages.map { mapOf("role" to it.role, "content" to it.content) },
        ) + extra

    public companion object {
        /** Model used by the plain-text chat helpers unless one is given. */
        public const val DEFAULT_CHAT_MODEL: String = "bitmeshai/chat-now-nano"
    }
}
