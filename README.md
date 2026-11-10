# Bitmesh Android SDK

Kotlin library for the [Bitmesh AI API](https://bitmesh.ai) **v2** on Android:
chat (streaming included), image, video, transcription, tools and the provider
proxy, with Firebase App Check for mobile keys.

v2 takes one bearer key (`bmk_...`), so there is nothing to sign. The SDK adds
what an app would otherwise write itself: the App Check token on every request,
streamed replies as a `Flow`, retries, multipart uploads and typed errors.

- **One runtime dependency:** `kotlinx-coroutines-core`. HTTP is
  `HttpURLConnection` and JSON is `org.json`, both built into Android.
- No Firebase dependency: you pass a function that returns the token.
- Android 6.0 (API 23) and later.

## Install

Add JitPack to `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}
```

Then the dependency, in your app's `build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.github.bitmeshai:bitmesh-android-sdk:1.0.0")
}
```

The library declares the `INTERNET` permission, so your manifest gets it merged in.

## Quick start

```kotlin
import ai.bitmesh.BitmeshClient
import ai.bitmesh.ChatMessage

val client = BitmeshClient("bmk_...")

// In a coroutine, e.g. viewModelScope.launch { ... }
val reply = client.chat(listOf(ChatMessage.user("Suggest three names for a hiking app.")))
println(reply.text)
println(reply.usage?.totalTokens)
```

Create one client and keep it, for example in your `Application` or a DI
singleton. Every call is a `suspend` function that does its network work on
`Dispatchers.IO`, so it is safe to call from the main thread.

Never hardcode the key in your app. Load it at runtime, from Remote Config or
your own backend. A key that is empty or doesn't start with `bmk_` (a v1
consumer key) throws `IllegalArgumentException` when the client is created.

## App Check (mobile keys)

If the key ships in your app, create it as a **mobile app** key in the Bitmesh
dashboard. The API then refuses any request on that key without a valid
[Firebase App Check](https://firebase.google.com/docs/app-check) token, so a key
pulled out of your APK is useless on its own. Server keys don't need this.

Set up App Check with Play Integrity in the Firebase console first; the
walkthrough is in the [API docs](https://bitmesh.ai/docs#app-check). Then:

```kotlin
// build.gradle.kts: firebase-appcheck-playintegrity, and
// kotlinx-coroutines-play-services for await()
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory
import kotlinx.coroutines.tasks.await

FirebaseAppCheck.getInstance().installAppCheckProviderFactory(
    PlayIntegrityAppCheckProviderFactory.getInstance(),
)

val client = BitmeshClient(
    bitmeshKey,
    BitmeshOptions(
        appCheckTokenProvider = {
            FirebaseAppCheck.getInstance().getAppCheckToken(false).await().token
        },
    ),
)
```

- The provider is called before **every** request, retries included. Firebase
  caches the token and refreshes it before it expires, so this is cheap.
- The token goes in `X-Firebase-AppCheck`. Result downloads (`fetchResult`)
  send neither the key nor the token.
- If the provider throws or returns no token, the request isn't sent and
  `BitmeshAppCheckTokenException` is thrown.

### Debug tokens

On an emulator, or before Firebase is set up, a Bitmesh debug token
(`bmdbg_...`, created on the key in the dashboard) can stand in for App Check.
It goes in `X-Bitmesh-Debug-Token`, and the token provider isn't called. Keep it
out of release builds:

```kotlin
BitmeshOptions(
    appCheckTokenProvider = { ... },
    debugToken = if (BuildConfig.DEBUG) BuildConfig.BITMESH_DEBUG_TOKEN else null,
)
```

## Streaming

```kotlin
val reply = StringBuilder()
client.streamChat(listOf(ChatMessage.user("Write a haiku about rain")))
    .collect { chunk ->
        reply.append(chunk.text)                       // "" on bookkeeping chunks
        chunk.usage?.let { println(it.totalTokens) }   // last chunk only
    }

// Raw chunks, in OpenAI's format
client.chatStream(mapOf("model" to "bitmeshai/chat-now-nano", "messages" to messages)).collect { json -> }
```

The request is billed once, when the stream ends. Cancel the collecting
coroutine (or leave the screen, with `viewModelScope`) to stop generating: the
connection is closed and you pay only for what was written. Errors before the
reply starts throw `BitmeshApiException` with their status; a provider failure
mid-stream throws one with status `502`.

## Endpoints

Request bodies are the ones in the [API reference](https://bitmesh.ai/docs), as
Kotlin maps; nested maps and lists become JSON. Replies are `org.json.JSONObject`.

```kotlin
val image = client.image(mapOf("model" to "bitmeshai/image-now", "prompt" to "a watercolour fox"))
val url = image.getJSONArray("data").getJSONObject(0).getString("url")
val png = client.fetchResult(url)

val job = client.video(mapOf("model" to "bitmeshai/video-now", "prompt" to "a paper plane at dusk"))
val status = client.getVideo(job.getString("id"))

val transcript = client.transcribeFile(
    BitmeshFile.fromFile(File(recordingPath)),
    mapOf("speech_models" to listOf("universal-2")),
)

val cutout = client.toolsGeneralBackgroundRemoval(
    BitmeshFile.fromBytes(bytes, "photo.jpg"),
    mapOf("return_form" to "crop"),
)
```

| Method | HTTP (under `/v2`) |
|---|---|
| `balance()` | `GET /balance` |
| `verify()` | `GET /test`, throws unless the key (and App Check token) is accepted |
| `chat(payload)`, `chat(messages, model, extra)` | `POST /chat` |
| `chatStream(payload)`, `streamChat(messages, model, extra)` | `POST /chat` with `"stream": true` |
| `image(payload)` | `POST /image` |
| `video(payload)` | `POST /video` |
| `getVideo(id, query)` | `GET /video/{id}` |
| `transcribeFile(audio, fields)` | `POST /transcribe-recorded` |
| `getTranscribeRecorded(id, query)` | `GET /transcribe-recorded/{id}` |
| `toolsGeneralBackgroundRemoval(image, fields)` | `POST /tools/general/background-removal` |
| `toolsPortraitTryOnClothes(files, fields)` | `POST /tools/portrait/try-on-clothes` |
| `toolsQueryAsyncTaskResult(taskId)` | `POST /tools/query-async-task-result` |
| `imageAnimate(payload)` | `POST /tools/video/image-animate` |
| `getImageAnimate(taskId, query)` | `GET /tools/video/image-animate/{task_id}` |
| `proxy(provider, path, method, body, query, headers)` | `/proxy/{provider}/{path}` |
| `proxyStream(provider, path, body, headers)` | `POST /proxy/{provider}/{path}`, streamed |
| `fetchResult(urlOrPath)` | `GET` a result link, no key sent |

Multipart fields travel as text: `true`/`false` as `1`/`0`, numbers as their
string form, `null`s left out. From a content `Uri`, read the bytes with
`contentResolver.openInputStream(uri)` and use `BitmeshFile.fromBytes`.

## Proxy

With a proxy key, send the provider's own request, minus its API key header:
Bitmesh adds your stored provider key. Bodies and replies pass through unchanged.

```kotlin
val proxy = BitmeshClient(proxyKey)

val response = proxy.proxy(
    "openai", "v1/chat/completions",
    body = mapOf("model" to "gpt-4o-mini", "messages" to listOf(mapOf("role" to "user", "content" to "Hello"))),
)
val reply = response.json()
val requestId = response.requestId          // X-Bitmesh-Request-Id

// Provider headers that select options are passed on
proxy.proxy("anthropic", "v1/messages", body = body, headers = mapOf("anthropic-version" to "2023-06-01"))

// Streaming: the provider's server-sent events
proxy.proxyStream("openai", "v1/chat/completions", body).collect { event ->
    if (!event.isDone) println(event.json())
}
```

`method` is `GET`, `POST`, `PUT` or `DELETE`.

## Errors

```kotlin
try {
    client.chat(messages)
} catch (e: BitmeshAppCheckTokenException) {
    // No App Check token on the device, so nothing was sent
} catch (e: BitmeshApiException) {
    // e.statusCode: 401 key or App Check, 402 no credits, 403 allowlist,
    // 422 validation, 429 a limit (e.retryAfterSeconds), 5xx
    // e.message: the API's reason. e.isAppCheckRejection, e.isTemporaryAppCheckFailure
} catch (e: BitmeshNetworkException) {
    // Never reached the API: offline, DNS, TLS, timeout
}
```

Retries are built in: a `429` is retried on any request, and a `5xx` or network
error on `GET` only, up to `maxRetries` times (default 2), honouring
`Retry-After`. Writes are never retried on a `5xx`, because the job may already
have been created and billed.

## Configuration

```kotlin
BitmeshOptions(
    baseUrl = "https://api.bitmesh.ai",   // the SDK adds /v2
    connectTimeoutMillis = 15_000,
    readTimeoutMillis = 120_000,          // per read; on a stream, between pieces
    maxRetries = 2,
    userAgent = "myapp/1.4.0",
    defaultHeaders = mapOf("X-App" to "myapp"),
)
```

## Development

```bash
./gradlew :bitmesh:testDebugUnitTest
```

Unit tests run against a local `HttpServer`. Live tests call the API and are
skipped unless you set a v2 hosted key (and, for the proxy, a proxy key):

```bash
BITMESH_TEST_API_KEY=bmk_... BITMESH_TEST_PROXY_KEY=bmk_... ./gradlew :bitmesh:testDebugUnitTest
```

## License

MIT
