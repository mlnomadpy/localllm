# Architecture

The app is one Gradle module — `:app` — containing both the Compose
UI and the foreground Service that hosts two inference engines (AICore
+ LiteRT-LM) and the HTTP server. Source is laid out flat under
`com.localllm.app` with three thin packages so far: `aicore/` (the ML
Kit GenAI wrapper), `embedding/` (ONNX Runtime + WordPiece), and
`rag/` (ObjectBox vector store, document chunker, tenant resolver).
The Service and routing layer still live in a single
`LLMServerService.kt` — splitting it into per-route files is on the
roadmap.

The line between "UI" and "service" is hard: the service is process-
global state (LRU caches, queue, request tracker), and the UI
observes it via `StateFlow`.

## Components at a glance

```
┌─────────────────────────────────────────────────────────────────┐
│                       Compose UI (MainActivity)                  │
│                                                                  │
│  Catalog · Dashboard · Console · Chat · Settings                 │
│  observes:                                                       │
│    ServerState (StateFlow<Status, Url, Error>)                   │
│    RequestTracker.queue / .stats (StateFlow)                     │
│    SettingsRepository.* (StateFlow per pref)                     │
└────┬────────────────────────────────────────────────────────┬───┘
     │ startForegroundService(...)                            │
     ▼                                                        │
┌─────────────────────────────────────────────────────────────────┐
│                LLMServerService (foreground, specialUse)         │
│                                                                  │
│  Ktor 3 (Netty)              ┌──────────────────────────────┐   │
│  ┌────────────────────────┐  │      EnginePool (LRU 2)      │   │
│  │ /health                │  │                              │   │
│  │ /v1/models             │  │  CachedEngine                │   │
│  │ /v1/chat/completions   │──▶  ├ Engine (LiteRT-LM)        │   │
│  │   ├ blocking           │  │  └ backend: "CPU" | "GPU"    │   │
│  │   └ streaming SSE      │  └──────────────────────────────┘   │
│  │     · heartbeat 10s    │  ┌──────────────────────────────┐   │
│  │     · error chunks     │  │   ConversationCache (LRU 4)  │   │
│  └────────────────────────┘  │   keyed by session_id +       │   │
│                              │   engineKey                   │   │
│  RequestTracker (queue +     └──────────────────────────────┘   │
│  history, atomic cap)        ┌──────────────────────────────┐   │
│                              │  Idle Monitor (30s tick)     │   │
│  inferenceMutex (Mutex)      │  · evict engines after N min │   │
│                              │  · auto-stop service         │   │
│  Wake lock (only while       └──────────────────────────────┘   │
│  inference runs)                                                 │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
            ┌────────────────────────────────┐
            │   liblitertlm_jni.so / native  │
            │   XNNPACK · libLiteRt.so · …   │
            └────────────────────────────────┘
```

## Inference path

There are two paths through `/v1/chat/completions`. The router
branches on `req.model` very early:

- **AICore path** (`req.model == "gemini-nano-aicore"`): no engine
  cache lookup, no `inferenceMutex`, no wake lock — AICore runs in
  the system service and handles its own serialization. The handler
  calls `AICoreEngine.checkStatusCode()` first; if the status isn't
  `STATUS_AVAILABLE` the request fails with a structured error.
  Otherwise the chat history is flattened (`flattenForAICore`) into a
  single `system: …\n\nuser: …\n\nassistant:` prompt and handed to
  `AICoreEngine.complete(...)` or `AICoreEngine.stream(...)`, both
  wrapped in `withTimeout(timeoutMs)`. **AICore requires the host
  app to be in the foreground** — backgrounded calls fail with
  ErrorCode 30 from the SDK.
- **LiteRT-LM path** (every other model id): the rest of this
  section.

### LiteRT-LM path

A request through `/v1/chat/completions` goes through:

1. **Body size guard** — `Content-Length` cap; fail fast with `413`.
2. **Parse + char cap** — Gson deserializes; `messages` content
   length summed and compared against `Settings.maxPromptChars`.
3. **Queue admission** — `RequestTracker.tryEnqueue` atomically
   admits the request iff in-flight count < cap. Returns `429
   Retry-After: 5` on overflow.
4. **Engine resolution** — `getOrCreateEngine(req)`:
    - Cache key is `model_<maxTokens|"model">_<backend>`.
    - Cache miss → call `buildEngine(modelFile, maxTokens,
      backend)`, which constructs an `EngineConfig` and calls
      `Engine.initialize()` (the blocking part — several seconds on a
      cold load).
    - **AUTO** path: try NPU → GPU → CPU. Every attempt is recorded
      with its result (`ok` / `failed: …` / `skipped: …`) and
      `duration_ms`; the first success wins and is cached. On Tensor
      SoCs, AUTO inserts a one-shot `NPU-primer` call before the real
      CPU attempt to unblock a known cold-init bug.
    - **Explicit CPU / GPU / NPU**: no fallback. Surface failures so
      users can debug. Errors include the attempts summary inline.
5. **Conversation resolution** — `resolveSession(req, handle,
   temperature, topK)`:
    - Stateless (empty `session_id`): build a fresh `Conversation`
      with all-but-the-last message as `initialMessages`, and treat
      the last user turn as the prompt to send.
    - Sessioned: look up cache by `<session_id>_<engineKey>`. Reuse
      iff sampling params match AND the prefix hash matches AND the
      new range collapses to one user turn (after filtering out
      assistant turns that are already in KV cache). Otherwise
      rebuild.
6. **Mutex + wake lock + timeout** — inference is serialized via
   `inferenceMutex`; a `PARTIAL_WAKE_LOCK` is held only while a
   request is in flight (auto-released after `requestTimeoutMs + 5s`
   as a safety net); `withTimeout(timeoutMs)` enforces the budget.
7. **Send + collect**:
    - Blocking: `conversation.sendMessage(Contents.of(prompt))`.
    - Streaming: `conversation.sendMessageAsync(Contents.of(prompt))`
      returns a `Flow<Message>` that emits incremental tokens. We
      diff against the previous snapshot to extract deltas, write
      each as an SSE chunk, and emit the `[DONE]` sentinel on Flow
      completion.
8. **Commit or invalidate** — sessioned requests `commitSession()`
   on success (updates `prefixHash` and `seenCount`) or
   `invalidateSession()` on failure (drops the entry). Stateless
   conversations are closed in `finally`.

## Two caches

**Engine LRU** (`LruCache<String, CachedEngine>`, capacity 2). Cache
key is `model_<maxTokens|"model">_<backend>`. Eviction closes the
underlying `Engine` and removes every cached `Conversation` keyed off
that engine (conversations can't outlive their parent).

**Conversation LRU** (`LruCache<String, CachedSession>`, capacity 4).
Cache key is `<session_id>_<engineKey>`. Eviction calls
`Conversation.close()`.

The engine cache is the expensive one — each loaded model takes
~2.5 GB of RAM and ~800 MB of XNNPACK kernel cache on disk after
first inference. Idle eviction runs every 30 seconds and unloads any
engine that hasn't been touched in `Settings.idleEvictMs` (default 5
min), but only when the inference mutex is free — eviction can never
interrupt an active request.

## Why the chat-template isn't in our code

LiteRT-LM reads the prompt template from the `.litertlm` bundle's
metadata. The engine handles `<start_of_turn>` markers, BOS / EOS
tokens, system-instruction placement — everything Gemma-4-specific
that used to live in our `formatGemmaPrompt(messages)` helper.

The HTTP layer just builds typed `Message` / `Contents` objects:

```kotlin
when (m.role) {
    "system"    -> LlmMessage.system(m.content)
    "assistant" -> LlmMessage.model(Contents.of(m.content), …)
    else        -> LlmMessage.user(m.content)
}
```

This means switching to a different `.litertlm` (say, a fine-tuned
Gemma 3 1B or a Qualcomm-NPU variant) requires zero code changes —
the runtime picks up the new template from the bundle.

## State boundaries

| State | Owner | Lifetime |
|---|---|---|
| `ServerState` (status / url / error) | process singleton | until process death |
| `RequestTracker` (queue + history) | process singleton | reset on `Service.onDestroy` |
| `LogManager` (in-memory ring buffer, 100 entries) | process singleton | process death |
| `SettingsRepository` (per-pref `StateFlow`) | process singleton (`applicationContext`) | process death |
| `engines` LRU | `LLMServerService` member | service lifetime |
| `sessions` LRU | `LLMServerService` member | service lifetime |
| `inferenceMutex` | `LLMServerService` member | service lifetime |

The UI never reads `SharedPreferences` directly — every Compose read
goes through `SettingsRepository.xxx.collectAsState()`, so slider
drags don't blow up the disk-read path.

## Error surfaces

The streaming path is the trickiest. Once `respondBytesWriter`
commits the `text/event-stream` headers, `call.respond(...)` becomes
a no-op. So the service hoists the active writer into the route's
`try` block and, on any catch, prefers writing an SSE error chunk
over the standard `call.respond`:

```kotlin
} catch (e: Exception) {
    val w = streamWriter
    if (w != null) writeSseError(w, e.message ?: "Unknown", "server_error", 500)
    else try { call.respond(InternalServerError, ErrorResponse(...)) } catch (_: Exception) {}
}
```

`writeSseError` swallows `IOException` because the client may have
already disconnected.

## File map

```
app/src/main/java/com/localllm/app/
├── ApiTypes.kt              OpenAI wire types (Gson @SerializedName)
├── BootReceiver.kt          autostart on device boot
├── LLMServerService.kt      foreground service: Ktor + LiteRT-LM + AICore routing + caches
├── LocalLLMApplication.kt   Application subclass
├── LogManager.kt            in-memory log ring buffer + Android Log
├── MainActivity.kt          Compose root, tab routing, file picker, download poll
├── MessageHelpers.kt        pure JVM-testable helpers extracted from the service
├── ModelCatalog.kt          AVAILABLE_MODELS + ModelInfo (sha256, requiredSocMarker)
├── RateLimiter.kt           per-client token bucket
├── RequestTracker.kt        atomic queue + history + stats StateFlow + client summaries
├── Settings.kt              DataStore-backed facade (with SharedPreferences migration)
├── SettingsRepository.kt    StateFlow-backed observable layer
├── Theme.kt                 dark Material 3 color scheme
├── aicore/
│   └── AICoreEngine.kt      ML Kit GenAI Prompt API wrapper (gemini-nano-aicore)
├── embedding/
│   ├── EmbeddingService.kt  ONNX Runtime + idle eviction
│   └── WordPieceTokenizer.kt BERT WordPiece in pure Kotlin
├── rag/
│   ├── Chunker.kt           paragraph-aware chunker with sliding-window fallback
│   ├── DocumentChunk.kt     ObjectBox entity (HNSW-indexed FloatArray)
│   ├── DocumentStore.kt     ingest / list / delete / search
│   └── TenantResolver.kt    per-client tenant isolation
└── ui/
    ├── AppTab.kt            enum + UiMessage data class
    ├── ChatBubble.kt / ChatEmptyState.kt / ChatTab.kt
    ├── ConsoleTab.kt        log viewer
    ├── DashboardTab.kt      live queue / stats / history + per-client summaries
    ├── DocumentApiClient.kt / DocumentsTab.kt
    ├── Header.kt            status dot helper
    ├── MarkdownText.kt      commonmark-backed renderer
    ├── ModelsTab.kt         catalog + custom URLs + import + SoC-aware chips
    └── SettingsTab.kt       settings UI, observes SettingsRepository
```

`LLMServerService.kt` is still a monolith (~2.3k lines). Splitting
routes / engine resolution / session management into per-file
packages (`server/routes/`, `inference/{litert,aicore}/`) is on the
roadmap but hasn't landed yet.

Tests live in `app/src/test/java/com/localllm/app/` —
`SettingsTest.kt`, `RequestTrackerTest.kt`, `ApiTypesTest.kt`,
`MessageHelpersTest.kt`, `RateLimiterTest.kt`, plus chunker tests
under `rag/`. Run via `./gradlew :app:testDebugUnitTest`.
