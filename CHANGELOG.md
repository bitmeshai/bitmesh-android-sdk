## 1.0.0

First release, for the Bitmesh API v2.

- `BitmeshClient("bmk_...")` sends the key as `Authorization: Bearer` to
  `{baseUrl}/v2/...`. Keys that don't start with `bmk_` are refused.
- Chat: `chat` (full body or plain-text messages), `chatStream` and
  `streamChat` for streamed replies as a `Flow`.
- Image, video, transcription and tools, with the method names of the other
  Bitmesh SDKs (`getVideo`, `transcribeFile`, `toolsPortraitTryOnClothes`, ...),
  and `fetchResult` for result links.
- The proxy for proxy keys: `proxy` and `proxyStream`.
- `balance()` and `verify()`.
- Firebase App Check through `appCheckTokenProvider`, and `debugToken` for
  emulators. No Firebase dependency.
- Retries on `429` (and on `5xx` or network errors for `GET`), honouring
  `Retry-After`.
- One runtime dependency, `kotlinx-coroutines-core`. HTTP is
  `HttpURLConnection` and JSON is `org.json`, both built into Android.
