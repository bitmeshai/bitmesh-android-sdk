package ai.bitmesh

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

/** A request as the test server received it. */
data class Received(val method: String, val path: String, val query: String?, val headers: Map<String, String>, val body: String) {
    fun json(): JSONObject = JSONObject(body)
}

class Reply(val status: Int = 200, val body: String = "{\"ok\":true}", val headers: Map<String, String> = emptyMap())

class BitmeshClientTest {
    private val key = "bmk_test0123456789"
    private lateinit var server: HttpServer
    private val received = CopyOnWriteArrayList<Received>()
    private var replies: (Received) -> Reply = { Reply() }

    private val baseUrl get() = "http://127.0.0.1:${server.address.port}"

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val request = Received(
                exchange.requestMethod,
                exchange.requestURI.rawPath,
                exchange.requestURI.rawQuery,
                exchange.requestHeaders.entries.associate { it.key.lowercase() to it.value.joinToString(", ") },
                exchange.requestBody.readBytes().toString(Charsets.UTF_8),
            )
            received += request
            val reply = replies(request)
            reply.headers.forEach { (k, v) -> exchange.responseHeaders.add(k, v) }
            val bytes = reply.body.toByteArray()
            exchange.sendResponseHeaders(reply.status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    @After
    fun stop() = server.stop(0)

    private fun client(options: (String) -> BitmeshOptions = { BitmeshOptions(baseUrl = it) }) = BitmeshClient(key, options(baseUrl))

    private fun fastRetries(base: String) = BitmeshOptions(baseUrl = base, maxRetries = 2)

    // Requests

    @Test
    fun requestsCarryTheBearerKeyOnV2Paths() = runBlocking<Unit> {
        client().chat(mapOf("model" to "m", "messages" to listOf(mapOf("role" to "user", "content" to "Hi"))))
        client().getVideo("job 1", mapOf("test" to "1"))

        val chat = received[0]
        assertEquals("POST", chat.method)
        assertEquals("/v2/chat", chat.path)
        assertEquals("Bearer $key", chat.headers["authorization"])
        assertEquals("application/json", chat.headers["content-type"])
        assertNull(chat.headers["x-payload-signature"])
        assertEquals("Hi", chat.json().getJSONArray("messages").getJSONObject(0).getString("content"))

        val video = received[1]
        assertEquals("GET", video.method)
        assertEquals("/v2/video/job%201", video.path)
        assertEquals("test=1", video.query)
    }

    @Test
    fun balanceAndVerify() = runBlocking<Unit> {
        replies = { Reply(body = if (it.path.endsWith("balance")) "{\"balance\":12.5}" else "{\"message\":\"ok\"}") }

        assertEquals(12.5, client().balance(), 0.0)
        client().verify()
        assertEquals(listOf("/v2/balance", "/v2/test"), received.map { it.path })
    }

    @Test
    fun aV1KeyIsRefused() {
        for (bad in listOf("", "  ", "ck_live_abc")) {
            try {
                BitmeshClient(bad)
                fail("Expected a refusal for \"$bad\"")
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    @Test
    fun customBaseUrlKeepsItsPath() = runBlocking<Unit> {
        BitmeshClient(key, BitmeshOptions(baseUrl = "$baseUrl/api/")).image(mapOf("prompt" to "p"))
        assertEquals("/api/v2/image", received.single().path)
    }

    @Test
    fun multipartFieldsTravelAsText() = runBlocking<Unit> {
        client().transcribeFile(
            BitmeshFile.fromBytes(byteArrayOf(1, 2, 3), "note.m4a"),
            mapOf("speech_models" to listOf("universal-2"), "punctuate" to true, "skipped" to null, "options" to mapOf("a" to 1)),
        )

        val request = received.single()
        assertEquals("/v2/transcribe-recorded", request.path)
        assertTrue(request.headers["content-type"]!!.startsWith("multipart/form-data; boundary="))
        assertTrue(request.body.contains("name=\"speech_models[0]\"\r\n\r\nuniversal-2\r\n"))
        assertTrue(request.body.contains("name=\"punctuate\"\r\n\r\n1\r\n"))
        assertTrue(request.body.contains("name=\"options[a]\"\r\n\r\n1\r\n"))
        assertFalse(request.body.contains("skipped"))
        assertTrue(request.body.contains("name=\"audio\"; filename=\"note.m4a\"\r\nContent-Type: audio/mp4"))
    }

    @Test
    fun resultDownloadsSendNoKey() = runBlocking<Unit> {
        replies = { Reply(body = "png-bytes") }
        val client = BitmeshClient(key, BitmeshOptions(baseUrl = baseUrl, appCheckTokenProvider = { "token-1" }))

        val bytes = client.fetchResult("/imgrslt/abc")

        assertEquals("png-bytes", bytes.toString(Charsets.UTF_8))
        assertEquals("/imgrslt/abc", received.single().path)
        assertNull(received.single().headers["authorization"])
        assertNull(received.single().headers["x-firebase-appcheck"])
    }

    // Errors and retries

    @Test
    fun errorsCarryStatusAndReason() = runBlocking<Unit> {
        replies = { Reply(402, "{\"error\":\"Insufficient balance\",\"message\":\"Add credits to continue.\"}") }
        try {
            client().chat(mapOf("model" to "m"))
            fail("Expected an error")
        } catch (e: BitmeshApiException) {
            assertEquals(402, e.statusCode)
            assertEquals("Add credits to continue.", e.message)
        }
    }

    @Test
    fun a429IsRetriedHonouringRetryAfter() = runBlocking<Unit> {
        replies = { if (received.size == 1) Reply(429, "{\"error\":\"Too many\"}", mapOf("Retry-After" to "0")) else Reply() }
        client(::fastRetries).chat(mapOf("model" to "m"))
        assertEquals(2, received.size)
    }

    @Test
    fun writesAreNotRetriedOnServerErrors() = runBlocking<Unit> {
        replies = { Reply(500, "{\"error\":\"boom\"}") }
        try {
            client(::fastRetries).chat(mapOf("model" to "m"))
            fail("Expected an error")
        } catch (e: BitmeshApiException) {
            assertEquals(500, e.statusCode)
        }
        assertEquals(1, received.size)
    }

    @Test
    fun getsAreRetriedOnServerErrors() = runBlocking<Unit> {
        replies = { if (received.size < 3) Reply(503, "{}") else Reply(body = "{\"balance\":1}") }
        assertEquals(1.0, client(::fastRetries).balance(), 0.0)
        assertEquals(3, received.size)
    }

    // App Check and debug tokens

    @Test
    fun appCheckTokenIsFetchedForEveryRequest() = runBlocking<Unit> {
        var calls = 0
        val client = BitmeshClient(key, BitmeshOptions(baseUrl = baseUrl, appCheckTokenProvider = { "token-${++calls}" }))

        client.balance()
        client.balance()

        assertEquals(listOf("token-1", "token-2"), received.map { it.headers["x-firebase-appcheck"] })
    }

    @Test
    fun debugTokenReplacesAppCheck() = runBlocking<Unit> {
        var calls = 0
        val client = BitmeshClient(
            key,
            BitmeshOptions(baseUrl = baseUrl, appCheckTokenProvider = { calls++; error("no App Check on an emulator") }, debugToken = "bmdbg_abc"),
        )

        client.balance()

        assertEquals("bmdbg_abc", received.single().headers["x-bitmesh-debug-token"])
        assertNull(received.single().headers["x-firebase-appcheck"])
        assertEquals(0, calls)
    }

    @Test
    fun aFailingProviderStopsTheRequest() = runBlocking<Unit> {
        for (provider in listOf<AppCheckTokenProvider>({ error("not set up") }, { null }, { " " })) {
            try {
                BitmeshClient(key, BitmeshOptions(baseUrl = baseUrl, appCheckTokenProvider = provider)).balance()
                fail("Expected BitmeshAppCheckTokenException")
            } catch (_: BitmeshAppCheckTokenException) {
            }
        }
        assertTrue(received.isEmpty())
    }

    @Test
    fun appCheckRejectionsAreRecognised() = runBlocking<Unit> {
        for (reason in listOf("App Check token has expired", "Debug token has expired", "App Check verification is temporarily unavailable")) {
            replies = { Reply(401, JSONObject().put("error", reason).toString()) }
            try {
                client().balance()
                fail("Expected an error")
            } catch (e: BitmeshApiException) {
                assertTrue(reason, e.isAppCheckRejection)
                assertEquals(reason.contains("temporarily"), e.isTemporaryAppCheckFailure)
            }
        }
        replies = { Reply(401, "{\"error\":\"Invalid API key\"}") }
        try {
            client().balance()
        } catch (e: BitmeshApiException) {
            assertFalse(e.isAppCheckRejection)
        }
    }

    // Streaming

    private val streamedReply = listOf(
        "data: {\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"\"}}]}",
        "",
        "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"There\"}}]}",
        "",
        "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\" are plenty.\"},\"finish_reason\":\"stop\"}]}",
        "",
        "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":51,\"completion_tokens\":4,\"total_tokens\":55}}",
        "",
        "data: [DONE]",
        "",
        "",
    ).joinToString("\n")

    @Test
    fun streamChatYieldsTextThenUsage() = runBlocking<Unit> {
        replies = { Reply(body = streamedReply, headers = mapOf("Content-Type" to "text/event-stream")) }

        val chunks = client().streamChat(listOf(ChatMessage.user("Hello")), model = "m").toList()

        val request = received.single()
        assertEquals("/v2/chat", request.path)
        assertEquals("text/event-stream", request.headers["accept"])
        assertTrue(request.json().getBoolean("stream"))
        assertEquals("There are plenty.", chunks.joinToString("") { it.text })
        assertEquals("stop", chunks[2].finishReason)
        assertEquals(55, chunks.last().usage?.totalTokens)
    }

    @Test
    fun aStreamErrorBeforeTheReplyThrows() = runBlocking<Unit> {
        replies = { Reply(402, "{\"error\":\"Insufficient balance\"}") }
        try {
            client().chatStream(mapOf("model" to "m")).toList()
            fail("Expected an error")
        } catch (e: BitmeshApiException) {
            assertEquals(402, e.statusCode)
            assertEquals("Insufficient balance", e.message)
        }
    }

    @Test
    fun anErrorEventMidStreamThrows() = runBlocking<Unit> {
        replies = {
            Reply(body = "data: {\"choices\":[{\"delta\":{\"content\":\"Hal\"}}]}\n\ndata: {\"error\":{\"message\":\"upstream overloaded\"}}\n\n")
        }
        val texts = mutableListOf<String>()
        try {
            client().streamChat(listOf(ChatMessage.user("Hi"))).collect { texts += it.text }
            fail("Expected an error")
        } catch (e: BitmeshApiException) {
            assertEquals("upstream overloaded", e.message)
        }
        assertEquals(listOf("Hal"), texts)
    }

    @Test
    fun stoppingEarlyCancelsTheStream() = runBlocking<Unit> {
        replies = { Reply(body = streamedReply) }
        val first = client().streamChat(listOf(ChatMessage.user("Hi"))).first { it.text.isNotEmpty() }
        assertEquals("There", first.text)
    }

    @Test
    fun parserHandlesNamesCommentsAndMultiLineData() {
        val parser = ServerSentEventParser()
        val events = listOf(": keep-alive", "event: message_start\r", "data: {\"x\":1}", "", "data: one", "data:two", "", "data: tail")
            .mapNotNull { parser.feed(it) } + listOfNotNull(parser.finish())

        assertEquals(
            listOf(ServerSentEvent("message_start", "{\"x\":1}"), ServerSentEvent(null, "one\ntwo"), ServerSentEvent(null, "tail")),
            events,
        )
    }

    // Proxy

    @Test
    fun proxyForwardsWithTheKeyAndProviderHeaders() = runBlocking<Unit> {
        replies = { Reply(body = "{\"content\":[{\"text\":\"Hi\"}]}", headers = mapOf("X-Bitmesh-Request-Id" to "42")) }

        val response = client().proxy(
            "anthropic",
            "/v1/messages",
            body = mapOf("model" to "claude", "max_tokens" to 10),
            headers = mapOf("anthropic-version" to "2023-06-01", "Authorization" to "Bearer sk-provider"),
        )

        val request = received.single()
        assertEquals("POST", request.method)
        assertEquals("/v2/proxy/anthropic/v1/messages", request.path)
        assertEquals("Bearer $key", request.headers["authorization"])
        assertEquals("2023-06-01", request.headers["anthropic-version"])
        assertEquals(10, request.json().getInt("max_tokens"))
        assertEquals("42", response.requestId)
        assertEquals("Hi", response.json().getJSONArray("content").getJSONObject(0).getString("text"))
    }

    @Test
    fun proxyStreamYieldsProviderEvents() = runBlocking<Unit> {
        replies = {
            Reply(body = "event: content_block_delta\ndata: {\"delta\":{\"text\":\"Hi\"}}\n\nevent: message_stop\ndata: {\"type\":\"message_stop\"}\n\n")
        }

        val events = client().proxyStream("anthropic", "v1/messages", mapOf("model" to "claude")).toList()

        assertTrue(received.single().json().getBoolean("stream"))
        assertEquals(listOf("content_block_delta", "message_stop"), events.map { it.event })
        assertEquals("Hi", events[0].json().getJSONObject("delta").getString("text"))
    }

    @Test
    fun proxyRefusesBadProvidersAndMethods() = runBlocking<Unit> {
        for (call in listOf<suspend () -> Unit>(
            { client().proxy("Open AI", "v1/models", method = "GET") },
            { client().proxy("openai", "v1/models", method = "PATCH") },
        )) {
            try {
                call()
                fail("Expected a refusal")
            } catch (_: IllegalArgumentException) {
            }
        }
        assertTrue(received.isEmpty())
    }
}
