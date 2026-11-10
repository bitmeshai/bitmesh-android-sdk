package ai.bitmesh

import org.json.JSONObject

/** One server-sent event: an optional `event:` name and its `data:` lines. */
public data class ServerSentEvent(
    /** The `event:` field, when the server named the event (Anthropic does). */
    val event: String? = null,
    /** The event's `data:` lines, joined with newlines. */
    val data: String,
    val id: String? = null,
) {
    /** OpenAI's end-of-stream marker, `data: [DONE]`. */
    val isDone: Boolean get() = data.trim() == "[DONE]"

    /** [data] parsed as a JSON object. */
    public fun json(): JSONObject = try {
        JSONObject(data)
    } catch (e: Exception) {
        throw BitmeshException("A streamed event is not a JSON object: ${data.take(200)}", e)
    }
}

/** Builds events from the lines of a `text/event-stream` body. */
internal class ServerSentEventParser {
    private var event: String? = null
    private var id: String? = null
    private val data = mutableListOf<String>()

    /** Feeds one line without its line break; returns an event when the line ends one. */
    fun feed(rawLine: String): ServerSentEvent? {
        val line = rawLine.removeSuffix("\r")
        if (line.isEmpty()) return finish()
        if (line.startsWith(":")) return null

        val colon = line.indexOf(':')
        val field = if (colon == -1) line else line.substring(0, colon)
        val value = if (colon == -1) "" else line.substring(colon + 1).removePrefix(" ")
        when (field) {
            "data" -> data += value
            "event" -> event = value
            "id" -> id = value
        }
        return null
    }

    /** Ends the current event, if it has data. Call once more at the end of the body. */
    fun finish(): ServerSentEvent? {
        val result = if (data.isEmpty()) null else ServerSentEvent(event, data.joinToString("\n"), id)
        event = null
        data.clear()
        return result
    }
}
