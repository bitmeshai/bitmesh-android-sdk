package ai.bitmesh

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Calls the live API. Skipped unless BITMESH_TEST_API_KEY (a v2 hosted key) is
 * set; the proxy test also needs BITMESH_TEST_PROXY_KEY.
 *
 *   BITMESH_TEST_API_KEY=bmk_... ./gradlew :bitmesh:testDebugUnitTest --tests '*LiveApiTest*'
 *
 * Optional: BITMESH_TEST_BASE_URL, BITMESH_TEST_MODEL, BITMESH_TEST_PROXY_PROVIDER,
 * BITMESH_TEST_PROXY_PATH, BITMESH_TEST_PROXY_MODEL. Chat calls are real and charged
 * (fractions of a cent); tools run in test mode. The real-time test also needs
 * BITMESH_TEST_AUDIO_PCM (16 kHz 16-bit mono PCM); a 20 s clip costs about 0.002.
 */
class LiveApiTest {
    private fun env(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }

    private val baseUrl = env("BITMESH_TEST_BASE_URL") ?: BitmeshOptions.DEFAULT_BASE_URL
    private val model = env("BITMESH_TEST_MODEL") ?: BitmeshClient.DEFAULT_CHAT_MODEL

    private fun hosted(): BitmeshClient {
        val key = env("BITMESH_TEST_API_KEY")
        assumeTrue("Set BITMESH_TEST_API_KEY to run live tests", key != null)
        return BitmeshClient(key!!, BitmeshOptions(baseUrl = baseUrl))
    }

    @Test
    fun verifyBalanceAndChat() = runBlocking<Unit> {
        val client = hosted()
        client.verify()
        val before = client.balance()

        val reply = client.chat(listOf(ChatMessage.user("Say hello in three words.")), model, mapOf("max_tokens" to 300))
        assertTrue(reply.text.orEmpty().isNotEmpty())
        assertTrue((reply.usage?.totalTokens ?: 0) > 0)

        val chunks = client.streamChat(listOf(ChatMessage.user("Count from 1 to 5.")), model, mapOf("max_tokens" to 300)).toList()
        assertTrue(chunks.size > 2)
        assertTrue(chunks.joinToString("") { it.text }.isNotEmpty())
        assertTrue((chunks.last { it.usage != null }.usage?.totalTokens ?: 0) > 0)

        assertTrue(client.balance() < before)
    }

    @Test
    fun toolsInTestMode() = runBlocking<Unit> {
        val image = javaClass.classLoader!!.getResourceAsStream("stool.png")!!.readBytes()
        val result = hosted().toolsGeneralBackgroundRemoval(
            BitmeshFile.fromBytes(image, "stool.png"),
            mapOf("test" to true, "return_form" to "crop"),
        )
        assertEquals("success", result.optString("status"))
    }

    @Test
    fun aBadKeyIsRefused() = runBlocking<Unit> {
        hosted()
        try {
            BitmeshClient("bmk_not_a_real_key_xyz", BitmeshOptions(baseUrl = baseUrl, maxRetries = 0)).balance()
            throw AssertionError("Expected a 401")
        } catch (e: BitmeshApiException) {
            assertEquals(401, e.statusCode)
        }
    }

    @Test
    fun proxy() = runBlocking<Unit> {
        val key = env("BITMESH_TEST_PROXY_KEY")
        assumeTrue("Set BITMESH_TEST_PROXY_KEY to run the proxy test", key != null)
        val client = BitmeshClient(key!!, BitmeshOptions(baseUrl = baseUrl))
        val provider = env("BITMESH_TEST_PROXY_PROVIDER") ?: "together"
        val path = env("BITMESH_TEST_PROXY_PATH") ?: "v1/chat/completions"
        val body = mapOf(
            "model" to (env("BITMESH_TEST_PROXY_MODEL") ?: "deepseek-ai/DeepSeek-V4.1-Flash-1017"),
            "messages" to listOf(mapOf("role" to "user", "content" to "Say hi in two words.")),
            "max_tokens" to 200,
        )

        val response = client.proxy(provider, path, body = body)
        assertEquals(200, response.statusCode)
        assertTrue(response.requestId != null)
        assertTrue(response.json().has("choices"))

        val events = client.proxyStream(provider, path, body).toList()
        assertTrue(events.size > 2)
        assertTrue(events.last().isDone)
    }

    @Test
    fun realtimeTranscription() = runBlocking<Unit> {
        val client = hosted()
        val path = env("BITMESH_TEST_AUDIO_PCM")
        assumeTrue("Set BITMESH_TEST_AUDIO_PCM to run the real-time test", path != null)
        val audio = java.io.File(path!!).readBytes()

        val session = client.transcribeRealtime(
            speechModel = RealtimeSpeechModel.UNIVERSAL_STREAMING_ENGLISH,
            formatTurns = true,
        )
        assertTrue(session.begin.id.isNotEmpty())
        val turns = async {
            session.events.filterIsInstance<RealtimeEvent.Turn>().toList().filter { it.endOfTurn }.map { it.transcript }
        }

        for (offset in audio.indices step 1600) {
            session.sendAudio(audio, offset, minOf(1600, audio.size - offset))
            delay(50)
        }
        val end = session.terminate()
        val transcript = turns.await().joinToString(" ")

        println("Transcript: $transcript")
        assertTrue(transcript.isNotEmpty())
        assertEquals(1000, session.closeCode)
        assertEquals(audio.size / 32000.0, end?.audioDurationSeconds ?: 0.0, 1.5)
        assertTrue((end?.sessionDurationSeconds ?: 0.0) > 0)

        try {
            BitmeshClient("bmk_not_a_key", BitmeshOptions(baseUrl = baseUrl)).transcribeRealtime()
            throw AssertionError("Expected 401")
        } catch (e: BitmeshApiException) {
            assertEquals(401, e.statusCode)
        }
    }
}
