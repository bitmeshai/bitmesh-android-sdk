package ai.bitmesh

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class RealtimeTranscriptionTest {
    private lateinit var server: MockWebServer
    private lateinit var client: BitmeshClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = BitmeshClient(
            "bmk_test",
            BitmeshOptions(baseUrl = server.url("/").toString().trimEnd('/'), debugToken = "bmdbg_x"),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    /** The server side of a session: sends Begin, records what arrives, answers Terminate. */
    private class FakeSession(private val onTerminate: (WebSocket) -> Unit) : WebSocketListener() {
        val received = CopyOnWriteArrayList<Any>()

        override fun onOpen(webSocket: WebSocket, response: Response) {
            webSocket.send("""{"type":"Begin","id":"sess_1","expires_at":1794471255}""")
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            received.add(bytes)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            received.add(text)
            if (JSONObject(text).optString("type") == "Terminate") onTerminate(webSocket)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, null)
        }
    }

    @Test
    fun connectsStreamsAudioAndEndsOnTerminate() = runBlocking<Unit> {
        val fake = FakeSession { socket ->
            socket.send("""{"type":"Turn","turn_order":0,"end_of_turn":false,"transcript":"Smoke from"}""")
            socket.send(
                """{"type":"Turn","turn_order":0,"end_of_turn":true,"turn_is_formatted":true,""" +
                    """"transcript":"Smoke from hundreds of wildfires.","language_code":"en","language_confidence":0.98,""" +
                    """"words":[{"text":"Smoke","start":0,"end":300}]}""",
            )
            socket.send("""{"type":"Termination","audio_duration_seconds":2,"session_duration_seconds":3}""")
            socket.close(1000, null)
        }
        server.enqueue(MockResponse().withWebSocketUpgrade(fake))

        val session = client.transcribeRealtime(
            sampleRate = 16_000,
            speechModel = RealtimeSpeechModel.UNIVERSAL_STREAMING_ENGLISH,
            formatTurns = true,
            query = mapOf("end_of_turn_confidence_threshold" to "0.7"),
        )

        val request = server.takeRequest(5, TimeUnit.SECONDS)!!
        val url = request.requestUrl!!
        assertEquals("/v2/transcribe-realtime", url.encodedPath)
        assertEquals("16000", url.queryParameter("sample_rate"))
        assertEquals("universal-streaming-english", url.queryParameter("speech_model"))
        assertEquals("true", url.queryParameter("format_turns"))
        assertEquals("0.7", url.queryParameter("end_of_turn_confidence_threshold"))
        assertEquals("Bearer bmk_test", request.getHeader("Authorization"))
        assertEquals("bmdbg_x", request.getHeader("X-Bitmesh-Debug-Token"))

        assertEquals("sess_1", session.begin.id)
        assertEquals(1794471255L, session.begin.expiresAt)

        session.sendAudio(ByteArray(1600) { 1 })
        session.sendAudio(ByteArray(3200) { 2 }, offset = 1600, length = 1600)
        val end = session.terminate()
        val events = session.events.toList()

        assertEquals(3.0, end?.sessionDurationSeconds)
        assertEquals(2.0, end?.audioDurationSeconds)
        assertEquals(1000, session.closeCode)
        assertTrue(session.isClosed)

        assertEquals(listOf("Begin", "Turn", "Turn", "Termination"), events.map { it.type })
        val last = events[2] as RealtimeEvent.Turn
        assertTrue(last.endOfTurn)
        assertTrue(last.isFormatted)
        assertEquals("Smoke from hundreds of wildfires.", last.transcript)
        assertEquals("en", last.languageCode)
        assertEquals(0.98, last.languageConfidence!!, 0.0)
        assertEquals("Smoke", last.words.getJSONObject(0).getString("text"))

        assertEquals(3, fake.received.size)
        assertEquals(ByteString.of(*ByteArray(1600) { 1 }), fake.received[0])
        assertEquals(ByteString.of(*ByteArray(1600) { 2 }), fake.received[1])
        assertEquals("Terminate", JSONObject(fake.received[2] as String).getString("type"))

        try {
            session.sendAudio(ByteArray(2))
            fail("Expected the session to refuse audio after it ended")
        } catch (_: IllegalStateException) {
        }
    }

    @Test
    fun aRefusedHandshakeThrowsTheApiError() = runBlocking<Unit> {
        server.enqueue(
            MockResponse().setResponseCode(402)
                .setBody("""{"error":"Insufficient balance","message":"The balance does not cover a minute of this model."}"""),
        )
        try {
            client.transcribeRealtime()
            fail("Expected 402")
        } catch (e: BitmeshApiException) {
            assertEquals(402, e.statusCode)
            assertEquals("The balance does not cover a minute of this model.", e.message)
        }

        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "5").setBody("""{"error":"Too many sessions open"}"""))
        try {
            client.transcribeRealtime()
            fail("Expected 429")
        } catch (e: BitmeshApiException) {
            assertEquals(429, e.statusCode)
            assertEquals(5L, e.retryAfterSeconds)
            assertEquals("Too many sessions open", e.message)
        }
    }

    @Test
    fun aSessionAtItsTimeLimitReports3008AfterTermination() = runBlocking<Unit> {
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send("""{"type":"Begin","id":"s"}""")
                    webSocket.send("""{"type":"Termination","audio_duration_seconds":60,"session_duration_seconds":60}""")
                    webSocket.close(3008, "Session reached its time limit")
                }
            }),
        )

        val session = client.transcribeRealtime()
        val types = mutableListOf<String>()
        try {
            session.events.collect { types.add(it.type) }
            fail("Expected the session to end with 3008")
        } catch (e: BitmeshRealtimeClosedException) {
            assertEquals(3008, e.code)
            assertTrue(e.isTimeLimit)
            assertEquals("Session reached its time limit", e.message)
        }
        assertEquals(listOf("Begin", "Termination"), types)
        assertEquals(60.0, session.terminate()?.sessionDurationSeconds)
        assertEquals(3008, session.closeCode)
    }

    @Test
    fun forceEndpointAndSendWriteJsonText() = runBlocking<Unit> {
        val fake = FakeSession { it.close(1000, null) }
        server.enqueue(MockResponse().withWebSocketUpgrade(fake))

        val session = client.transcribeRealtime()
        session.forceEndpoint()
        session.send(mapOf("type" to "UpdateConfiguration", "max_turn_silence" to 500))
        assertNull(session.terminate())

        assertEquals(
            listOf("ForceEndpoint", "UpdateConfiguration", "Terminate"),
            fake.received.map { JSONObject(it as String).getString("type") },
        )
        assertEquals(500, JSONObject(fake.received[1] as String).getInt("max_turn_silence"))
    }

    @Test
    fun closeEndsTheSessionWithoutTermination() = runBlocking<Unit> {
        server.enqueue(MockResponse().withWebSocketUpgrade(FakeSession { }))
        val session = client.transcribeRealtime()
        session.close()
        assertNull(session.terminate(timeoutMillis = 5_000))
        assertEquals(1000, session.closeCode)
    }

    @Test(expected = IllegalArgumentException::class)
    fun sampleRateMustBePositive() = runBlocking<Unit> {
        client.transcribeRealtime(sampleRate = 0)
    }

    @Test
    fun unknownMessagesAreKept() {
        val event = RealtimeEvent.parse(JSONObject("""{"type":"SpeechStarted","x":1}"""))
        assertTrue(event is RealtimeEvent.Other)
        assertEquals("SpeechStarted", event.type)
        assertEquals(1, event.raw.getInt("x"))
    }
}
