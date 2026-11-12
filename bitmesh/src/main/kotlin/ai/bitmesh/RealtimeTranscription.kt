package ai.bitmesh

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject

/** The real-time transcription models, for `transcribeRealtime(speechModel = ...)`. */
public object RealtimeSpeechModel {
    /** The default: 32 languages, switching mid-sentence, punctuated and cased. */
    public const val UNIVERSAL_3_6_PRO: String = "universal-3-6-pro"

    /** English only. Lowercase without punctuation unless `formatTurns` is set. */
    public const val UNIVERSAL_STREAMING_ENGLISH: String = "universal-streaming-english"

    /** English, Spanish, German, French, Portuguese and Italian, one per turn. */
    public const val UNIVERSAL_STREAMING_MULTILINGUAL: String = "universal-streaming-multilingual"
}

/**
 * A message from a real-time transcription session: one [Begin], a [Turn] each
 * time the transcript changes, and [Termination] last. Message types this SDK
 * doesn't know come as [Other].
 */
public sealed class RealtimeEvent(
    /** The message as received. */
    public val raw: JSONObject,
) {
    /** The message's `type`. */
    public val type: String get() = raw.optString("type")

    /** The session started. */
    public class Begin(raw: JSONObject) : RealtimeEvent(raw) {
        /** The session id. */
        public val id: String = raw.optString("id")

        /** The latest time the session may run to, in Unix seconds: an hour, or less when the balance covers less. */
        public val expiresAt: Long? = if (raw.has("expires_at")) raw.optLong("expires_at") else null

        override fun toString(): String = "Begin($id)"
    }

    /**
     * The transcript of one turn so far. The same turn is sent again each time
     * it changes; it is final when [endOfTurn] is true, so keep the last one of
     * each [turnOrder].
     */
    public class Turn(raw: JSONObject) : RealtimeEvent(raw) {
        /** Counts the turns of the session from 0. */
        public val turnOrder: Int = raw.optInt("turn_order")

        /** The words of the turn so far. */
        public val transcript: String = raw.optString("transcript")

        /** True when the turn is finished and [transcript] won't change again. */
        public val endOfTurn: Boolean = raw.optBoolean("end_of_turn")

        /** True when [transcript] is punctuated and cased. */
        public val isFormatted: Boolean = raw.optBoolean("turn_is_formatted")

        /** The turn's language, with `languageDetection = true`. */
        public val languageCode: String? = raw.optString("language_code").takeIf { it.isNotEmpty() }

        /** How sure the model is of [languageCode], from 0 to 1. */
        public val languageConfidence: Double? =
            if (raw.has("language_confidence")) raw.optDouble("language_confidence") else null

        /** Word-level detail (`text`, `start`, `end`, `confidence`), as received. */
        public val words: JSONArray get() = raw.optJSONArray("words") ?: JSONArray()

        override fun toString(): String = "Turn($turnOrder${if (endOfTurn) ", final" else ""}: $transcript)"
    }

    /** The session ended. [sessionDurationSeconds] is the time billed. */
    public class Termination(raw: JSONObject) : RealtimeEvent(raw) {
        /** Seconds of audio the session received. */
        public val audioDurationSeconds: Double? =
            if (raw.has("audio_duration_seconds")) raw.optDouble("audio_duration_seconds") else null

        /** Seconds the session was open: the time it is billed for. */
        public val sessionDurationSeconds: Double? =
            if (raw.has("session_duration_seconds")) raw.optDouble("session_duration_seconds") else null

        override fun toString(): String = "Termination(audio: $audioDurationSeconds s, session: $sessionDurationSeconds s)"
    }

    /** A message type this SDK doesn't parse; [raw] has all of it. */
    public class Other(raw: JSONObject) : RealtimeEvent(raw) {
        override fun toString(): String = "Other($type)"
    }

    public companion object {
        /** Parses one message from the server. */
        public fun parse(json: JSONObject): RealtimeEvent = when (json.optString("type")) {
            "Begin" -> Begin(json)
            "Turn" -> Turn(json)
            "Termination" -> Termination(json)
            else -> Other(json)
        }
    }
}

/**
 * A real-time transcription session closed other than normally (close code
 * 1000). It ends the session's `events`, after any `Termination`.
 *
 * `3008`: the session reached its time limit (an hour, or less when the
 * balance covers less). `1001`: the server restarted; reconnect, the session
 * is billed for the time it ran. `1011`: the transcription service failed.
 * Other codes from 3000 to 4999 come from the transcription service, with the
 * reason in [message]. `1006`: the connection was lost.
 */
public class BitmeshRealtimeClosedException(
    /** The WebSocket close code. */
    public val code: Int,
    reason: String,
    cause: Throwable? = null,
) : BitmeshException(reason, cause) {
    /** True for 3008, a session that reached its time limit. */
    public val isTimeLimit: Boolean get() = code == 3008
}

/**
 * An open real-time transcription session, from [BitmeshClient.transcribeRealtime].
 *
 * Send audio with [sendAudio] as it is recorded, collect the transcript from
 * [events], and call [terminate] when you are done:
 *
 * ```
 * val session = client.transcribeRealtime(sampleRate = 16_000)
 * scope.launch {
 *     session.events.collect { event ->
 *         if (event is RealtimeEvent.Turn && event.endOfTurn) println(event.transcript)
 *     }
 * }
 * session.sendAudio(pcmChunk) // 16-bit mono PCM from AudioRecord, about 50 ms at a time
 * val end = session.terminate()
 * ```
 *
 * The session is billed for the time it is open, silence included, so end it
 * as soon as you are done.
 */
public class RealtimeTranscription internal constructor() {
    private lateinit var socket: WebSocket
    private val channel = Channel<RealtimeEvent>(Channel.UNLIMITED)
    private val begun = CompletableDeferred<RealtimeEvent.Begin>()
    private val ended = CompletableDeferred<RealtimeEvent.Termination?>()
    private val lock = Any()
    private var termination: RealtimeEvent.Termination? = null
    private var ending = false
    private var closedByClient = false
    private var finishedCode: Int? = null

    /** The session's `Begin` message. */
    public lateinit var begin: RealtimeEvent.Begin
        private set

    /**
     * Every message of the session, in order, from `Begin` to `Termination`.
     * Messages are kept until collected. It ends when the session closes,
     * throwing [BitmeshRealtimeClosedException] for a close other than a normal
     * one. Collect it once.
     */
    public val events: Flow<RealtimeEvent> = channel.receiveAsFlow()

    /** True once the connection has closed. */
    public val isClosed: Boolean get() = synchronized(lock) { finishedCode != null }

    /** The close code once closed: 1000 for a normal end. */
    public val closeCode: Int? get() = synchronized(lock) { finishedCode }

    /**
     * Sends a piece of audio: raw 16-bit little-endian mono PCM at the session's
     * sample rate (or μ-law with `encoding = "pcm_mulaw"`). Send it as it is
     * recorded, about 50 ms at a time: 1,600 bytes at 16 kHz.
     */
    public fun sendAudio(audio: ByteArray, offset: Int = 0, length: Int = audio.size - offset) {
        checkOpen()
        socket.send(audio.toByteString(offset, length))
    }

    /** Ends the current turn now, without waiting for a pause. */
    public fun forceEndpoint() {
        send(mapOf("type" to "ForceEndpoint"))
    }

    /** Sends a JSON control message, such as `mapOf("type" to "UpdateConfiguration", ...)`. */
    public fun send(message: Map<String, Any?>) {
        checkOpen()
        socket.send(Transport.toJson(message).toString())
    }

    /**
     * Ends the session: turns still in progress arrive, then `Termination`, then
     * the connection closes. Returns the `Termination` message, or null when the
     * session closed without one.
     *
     * Closes the connection itself if the server hasn't within [timeoutMillis].
     */
    public suspend fun terminate(timeoutMillis: Long = 30_000): RealtimeEvent.Termination? {
        val send = synchronized(lock) {
            val first = !ending && finishedCode == null
            ending = true
            first
        }
        if (send) socket.send("""{"type":"Terminate"}""")
        withTimeoutOrNull(timeoutMillis) { ended.await() }?.let { return it }
        if (!ended.isCompleted) close()
        return ended.await()
    }

    /**
     * Closes the connection straight away. Prefer [terminate], which lets the
     * last turn arrive. The session is billed up to the close either way.
     */
    public fun close() {
        synchronized(lock) {
            ending = true
            closedByClient = true
        }
        if (!socket.close(1000, null)) socket.cancel()
    }

    private fun checkOpen() {
        check(synchronized(lock) { !ending && finishedCode == null }) {
            "The real-time transcription session has ended."
        }
    }

    internal val listener = object : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            val json = try {
                JSONObject(text)
            } catch (_: Exception) {
                return
            }
            val event = RealtimeEvent.parse(json)
            if (event is RealtimeEvent.Begin && !begun.isCompleted) {
                begin = event
                begun.complete(event)
            }
            if (event is RealtimeEvent.Termination) synchronized(lock) { termination = event }
            channel.trySend(event)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) = Unit

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, null)
            finish(code, reason, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            finish(code, reason, null)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (!begun.isCompleted) {
                val status = response?.code
                begun.completeExceptionally(
                    if (status != null && status != 101) {
                        BitmeshApiException.from(
                            status,
                            try {
                                response.body?.string().orEmpty()
                            } catch (_: Exception) {
                                ""
                            },
                            response.header("Retry-After")?.trim()?.toLongOrNull(),
                            response.header("X-Bitmesh-Request-Id"),
                        )
                    } else {
                        BitmeshNetworkException("Could not open the real-time transcription connection: ${t.message}", t)
                    },
                )
            }
            finish(1006, t.message.orEmpty(), t)
        }
    }

    private fun finish(serverCode: Int, reason: String, cause: Throwable?) {
        val (code, end) = synchronized(lock) {
            if (finishedCode != null) return
            val code = if (closedByClient) 1000 else serverCode
            finishedCode = code
            code to termination
        }
        if (code == 1000) {
            channel.close()
        } else {
            channel.close(
                BitmeshRealtimeClosedException(
                    code,
                    reason.ifEmpty { if (code == 1006) "The connection was lost." else "The session closed with code $code." },
                    cause,
                ),
            )
        }
        if (!begun.isCompleted) {
            begun.completeExceptionally(BitmeshRealtimeClosedException(code, reason.ifEmpty { "The session closed before it began." }))
        }
        ended.complete(end)
    }

    internal companion object {
        /** Shared by every session; WebSockets don't use its connection pool. */
        private val baseClient: OkHttpClient by lazy { OkHttpClient() }

        suspend fun open(
            url: String,
            headers: Map<String, String>,
            options: BitmeshOptions,
        ): RealtimeTranscription {
            val session = RealtimeTranscription()
            val client = baseClient.newBuilder()
                .connectTimeout(options.connectTimeoutMillis.toLong(), TimeUnit.MILLISECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .pingInterval(20, TimeUnit.SECONDS)
                .build()
            val request = Request.Builder().url(url).apply {
                headers.forEach { (name, value) -> header(name, value) }
            }.build()
            session.socket = client.newWebSocket(request, session.listener)

            try {
                // Returns once the server's Begin arrives; a refused handshake fails here.
                withTimeout(options.connectTimeoutMillis.toLong() + options.readTimeoutMillis) { session.begun.await() }
            } catch (e: TimeoutCancellationException) {
                session.socket.cancel()
                throw BitmeshNetworkException("The real-time transcription connection timed out.", e)
            } catch (e: Throwable) {
                session.socket.cancel()
                throw e
            }
            return session
        }
    }
}
