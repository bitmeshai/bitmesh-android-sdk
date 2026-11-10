package ai.bitmesh

import org.json.JSONObject

public data class ChatMessage(val role: String, val content: String) {
    public companion object {
        public fun system(content: String): ChatMessage = ChatMessage("system", content)
        public fun user(content: String): ChatMessage = ChatMessage("user", content)
        public fun assistant(content: String): ChatMessage = ChatMessage("assistant", content)
    }
}

/** Tokens a request was billed for. */
public data class ChatUsage(val promptTokens: Int?, val completionTokens: Int?, val totalTokens: Int?) {
    internal companion object {
        fun from(json: JSONObject?): ChatUsage? = json?.let {
            ChatUsage(it.intOrNull("prompt_tokens"), it.intOrNull("completion_tokens"), it.intOrNull("total_tokens"))
        }
    }
}

/** A chat completion. [raw] holds the whole reply, in OpenAI's format. */
public class ChatResponse(public val raw: JSONObject) {
    private val first: JSONObject? get() = raw.optJSONArray("choices")?.optJSONObject(0)

    /** The reply's text, or `null` when the model wrote nothing. */
    public val text: String? get() = first?.optJSONObject("message")?.stringOrNull("content")?.takeIf { it.isNotEmpty() }

    /** `stop`, or `length` when `max_tokens` ran out first. */
    public val finishReason: String? get() = first?.stringOrNull("finish_reason")

    /** The model that answered, as the provider names it. */
    public val model: String? get() = raw.stringOrNull("model")

    public val usage: ChatUsage? get() = ChatUsage.from(raw.optJSONObject("usage"))

    override fun toString(): String = raw.toString()
}

/** One piece of a streamed reply. */
public class ChatChunk(public val raw: JSONObject) {
    private val first: JSONObject? get() = raw.optJSONArray("choices")?.optJSONObject(0)

    /** The text this piece adds, or `""`. */
    public val text: String get() = first?.optJSONObject("delta")?.stringOrNull("content").orEmpty()

    public val finishReason: String? get() = first?.stringOrNull("finish_reason")

    /** Set on the last piece only. */
    public val usage: ChatUsage? get() = ChatUsage.from(raw.optJSONObject("usage"))

    override fun toString(): String = raw.toString()
}

internal fun JSONObject.stringOrNull(key: String): String? = if (isNull(key)) null else optString(key)

internal fun JSONObject.intOrNull(key: String): Int? = if (isNull(key)) null else optInt(key)
