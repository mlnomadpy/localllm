# LocalLLM Edge Server

An on-device, OpenAI-compatible LLM HTTP server for Android. Exposes a
`/v1/chat/completions` endpoint backed by two engines: Google's **AICore
(Gemini Nano)** system model on Pixel-class devices, or any **LiteRT-LM**
`.litertlm` bundle. No cloud, no remote API key, no data leaves the device.

The app pairs a Compose UI for managing models and watching live request
stats with a foreground Service that hosts both the Ktor HTTP server and the
inference runtimes.

## What it does

- **AICore (Gemini Nano)** via ML Kit GenAI Prompt API
  (`com.google.mlkit:genai-prompt:1.0.0-beta2`). Exposed as the magic model
  id **`gemini-nano-aicore`** — no `.litertlm` file, no download from us;
  the AICore system service provisions weights. Requires Pixel 8+ with the
  AICore Developer Preview enrolled. The host app must stay in the
  foreground while a request is in flight (AICore returns ErrorCode 30 —
  "Background usage is blocked" — otherwise).
- **LiteRT-LM** `.litertlm` bundles via
  `com.google.ai.edge.litertlm:litertlm-android:0.12.0`. Catalog ships
  **Gemma 4 E2B IT** and **Gemma 4 E4B IT** plus eight NPU-compiled
  **Gemma 3 1B** variants (Qualcomm SM8550/8650/8750/8850, MediaTek
  MT6989/6991/6993, and Google Tensor G5 for Pixel 10).
- Runs an embedded Ktor 3 HTTP server. Endpoints:
  - `GET /health` — liveness + which backend each cached LiteRT engine
    initialized on (with the full attempt chain)
  - `GET /v1/models` — lists `.litertlm` files on disk, ONNX embedding
    models, plus a virtual `gemini-nano-aicore` entry
  - `POST /v1/chat/completions` — OpenAI-style chat completion, streaming
    or not. Pick `gemini-nano-aicore` to route through AICore; pick any
    `.litertlm` model id (e.g. `gemma-4-e2b`) to route through LiteRT-LM.
  - `POST /v1/embeddings`, `POST /v1/documents`, `POST /v1/search` — RAG
    surface (ONNX embeddings + ObjectBox vector store).
- Real **SSE error chunks**: when inference fails mid-stream, the client gets
  a final `data: {"error":{...}}` followed by `[DONE]` — no silent connection
  drops.
- **LiteRT backend chain**: AUTO tries NPU → GPU → CPU and records every
  attempt in `/health`. Explicit CPU/GPU/NPU selection skips the fallback so
  you can debug. AICore doesn't expose backend selection — the AICore system
  service picks NPU/GPU/CPU on the device.
- **SHA256 model verification** on download: the catalog declares the
  expected hash; mismatched downloads are deleted and surfaced to the user.
- Runs as a foreground service (`specialUse` type, `on_device_inference`
  subtype) with a persistent notification and a Stop action.
- Can autostart on app launch and on device boot.
- Optionally binds `0.0.0.0` so other devices on the same Wi-Fi can use it.

## Tabs

- **Catalog** — download a built-in model, import a `.litertlm` file from the
  device, or pull from a custom URL configured in Settings. Delete to free
  space. SHA256 is verified automatically on built-in downloads.
- **Dashboard** — live queue, in-flight request, recent history (cap 50), and
  cumulative stats (counts, avg latency, avg tok/s, error rate).
- **Console** — live in-memory log stream from the service.
- **Chat** — a usable test harness against the local server. Features:
  friendly model labels in the dropdown (instead of raw filenames), a Stop
  button that actually cancels the in-flight request (server sees the
  disconnect and calls `cancelProcess()`), a live `streaming — 12.4 tok/s ·
  73 tokens` subtitle, long-press copy on any chat bubble, and a collapsible
  system-prompt field.
- **Settings** — port, LAN bind, max tokens, temperature, top-k, LiteRT
  backend (AUTO/CPU/GPU/NPU — NPU pill auto-enables when a vendor delegate
  is detected), API key, CORS toggle, request limits (timeout, queue depth,
  prompt cap, per-client rate limit), background efficiency (idle eviction,
  auto-stop, wake lock), start-on-boot, autostart, and custom model URLs.
  Reads are backed by `StateFlow` so slider drags don't blow up the
  DataStore I/O path.

## Calling the server

Default port is **8099** (configurable in Settings).

AICore (Gemini Nano) — no download required on a supported device:

```bash
curl http://127.0.0.1:8099/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{
    "model": "gemini-nano-aicore",
    "messages": [{"role": "user", "content": "Hello"}]
  }'
```

LiteRT-LM model — pick an `id` from `GET /v1/models` (filename minus
`.litertlm`):

```bash
curl -N http://127.0.0.1:8099/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{
    "model": "gemma-4-e2b",
    "messages": [{"role": "user", "content": "Tell me a story."}],
    "stream": true,
    "temperature": 0.7,
    "top_k": 40,
    "max_tokens": 256
  }'
```

**AICore foreground constraint.** AICore returns ErrorCode 30
("Background usage is blocked") if the host app isn't visible while a
request is in flight. If you drive the server from an external client
(curl, a PWA, another app), keep LocalLLM in the foreground. LiteRT-LM
models have no such constraint.

From a laptop on the same Wi-Fi, enable **Bind to LAN** in Settings, then hit
`http://<phone-ip>:8099/...`. From the Android emulator, use `10.0.2.2` for
the host's localhost.

### AICore device support

| Device | Status |
|---|---|
| Pixel 10 Pro XL (mustang) | Works end-to-end. |
| Pixel 10 (frankel, `53061FDCR000XR`) | AICore is installed (build `aicore_20260430.00_RC07`) but Gemini Nano feature 646 isn't provisioned — returns `ErrorCode 606 FEATURE_NOT_FOUND`. Use a LiteRT-LM model on this device. |
| Pixel 8 / 8 Pro / 9 series | Expected to work when enrolled in the AICore Developer Preview. |
| Everything else | LiteRT-LM only. |

KV-cache reuse via `session_id` (see below) is LiteRT-LM only — AICore
calls are stateless from our side; every chat completion against
`gemini-nano-aicore` re-flattens the message history into a single
prompt.

## Multi-turn (`session_id`)

To take advantage of KV-cache reuse across turns, pass a stable `session_id`
on each request in a conversation:

```jsonc
// Turn 1
POST /v1/chat/completions
{
  "model": "gemma-4-e2b",
  "session_id": "alice-2026-05-10",
  "messages": [
    {"role": "user", "content": "Hi"}
  ]
}

// Turn 2 — same session_id, full message history echoed (OpenAI convention)
POST /v1/chat/completions
{
  "model": "gemma-4-e2b",
  "session_id": "alice-2026-05-10",
  "messages": [
    {"role": "user", "content": "Hi"},
    {"role": "assistant", "content": "Hello! How can I help?"},
    {"role": "user", "content": "Tell me about kernels"}
  ]
}
```

Behavior:
- Empty / omitted `session_id` → fresh stateless conversation every request
  (default).
- Non-empty `session_id` → server caches the LiteRT-LM `Conversation` and on
  follow-up requests only sends the **new user turns**. Assistant turns
  echoed by the client are skipped because they're already in the model's
  KV cache.
- The server validates the replayed prefix via a hash. If the prefix doesn't
  match what was recorded — e.g., you rewound, edited, or used the same
  `session_id` for a different conversation — the conversation is rebuilt
  from scratch.
- Cache holds at most 4 conversations; oldest evicted. Conversations are
  also evicted along with their parent engine when the engine is unloaded
  (idle eviction or LRU pressure).
- Changing `temperature` or `top_k` mid-session triggers a rebuild (those
  are conversation-construction params in LiteRT-LM's `SamplerConfig`).

On a 10-turn conversation, request N pays only the cost of prefilling turn
N's new user message, not the full history.

## Custom models

Any LiteRT-LM `.litertlm` bundle should work. Two ways to add one:

1. **Custom URL** — Settings → Custom model URLs, one URL per line. Must end
   in `.litertlm`. The Catalog tab will show it as a downloadable entry.
   Custom-URL downloads are not SHA256-verified — bring-your-own integrity.
2. **Local import** — Catalog → "Import .litertlm file from device" and pick
   a `.litertlm` from the file picker.

## Backend selection (LiteRT-LM only)

AICore doesn't expose backend selection — the AICore system service picks
NPU/GPU/CPU internally. The settings below only affect requests routed to
`.litertlm` models.

In Settings → Inference defaults → **Backend** you can choose:

- **AUTO** (default for LiteRT) — try NPU → GPU → CPU. Every attempt is
  recorded and surfaced in `GET /health` under `engines[].attempts`. The
  first one that initializes wins and is cached.
- **CPU** — force `Backend.CPU()`. Slowest but most portable; uses XNNPACK
  under the hood. On Google Tensor SoCs the runtime needs a one-shot NPU
  primer call to unblock a cold CPU init (recorded in the attempt chain as
  `NPU-primer=expected-fail` followed by `CPU=ok`).
- **GPU** — force `Backend.GPU()`. Strict, no fallback. Fails at request
  time if the OpenCL/OpenGL delegate can't initialize.
- **NPU/TPU** — force `Backend.NPU(nativeLibraryDir)`. Auto-enabled only
  when a vendor delegate `.so` is detected (`libqnn*.so`, `*hexagon*`,
  `*neuron*`, `libapu*`) or the Google Tensor dispatch lib is bundled
  (Pixel 6/9/10). Requires an SoC-matched `.litertlm` from the Catalog —
  picking the wrong one fails with `TF_LITE_AUX not found in the model`.

Switching the backend forces an engine rebuild on the next request (cold
load, several seconds). The engine cache key is `model + maxTokens + backend`.

For the Pixel 10 Tensor G5, the **`gemma3-1b-it-npu-tensor-g5`** catalog
entry runs on the TPU via the bundled `libLiteRtDispatch_GoogleTensor.so`
— no extra runtime install. For Snapdragon / Dimensity, pick the SoC-
matched Gemma 3 1B NPU variant; the Catalog UI badges the one that
matches your device.

## Multi-app use & limits

The server is designed to be hit by multiple apps on the same device (or LAN
when **Bind to LAN** is enabled). Settings → Request limits exposes:

- **Request timeout** (default 120s) — beyond this the server emits an SSE
  error chunk for streaming requests or a `408` for blocking requests, and
  calls `Conversation.cancelProcess()` so the native engine actually stops
  burning compute.
- **Max queue depth** (default 8) — beyond this we return `429 Too Many
  Requests` with `Retry-After: 5`. The atomic `tryEnqueue` ensures the cap
  is honored even under concurrent enqueues.
- **Max prompt chars** (default 100,000) — beyond this we return `413
  Payload Too Large`.

Settings → Security exposes an optional **API key**. When set, every
`/v1/chat/completions` and `/v1/models` request must carry `Authorization:
Bearer <key>`. `/health` stays unauthenticated for liveness probes. The CORS
plugin is **off** by default — only same-host or LAN-local clients can call
the server. Toggle Settings → Allow CORS to install `anyHost()` if you want
browser-based clients to call the API.

Each request is logged with its remote IP and User-Agent, so you can tell
which app is doing what.

## Background efficiency

Settings → Background:

- **Unload models after N min idle** (default 5) — frees the engine LRU's
  ~2.5 GB per Gemma 4 E2B when nothing is happening. Set to `0` to disable.
- **Auto-stop server after N min idle** (default `0`, disabled) — stops the
  foreground service entirely. The user will need to relaunch the app or
  reboot.
- **Keep CPU awake during inference** (default on) — holds a
  `PARTIAL_WAKE_LOCK` only while a request is running, so Doze can't
  throttle inference mid-stream. The lock has a hard timeout slightly above
  the configured request timeout — defense against any bug that forgets to
  release it.

The idle monitor is a single 30s-tick coroutine inside the service.
Eviction only runs when the inference mutex isn't held, so it can never
interrupt an active request.

## Endpoints

| Path | Notes |
|---|---|
| `GET /health` | Liveness. Returns `status`, `service`, `version`, `queue_depth`, `engines_loaded`, and an `engines` array of `{key, backend, attempts[]}` so callers can see every LiteRT backend the resolver tried (`ok` / `failed: ...` / `skipped: ...` with `duration_ms`). Never gated by the API key. |
| `GET /v1/models` | Gated by API key when set. Lists `.litertlm` files on disk, ONNX embedding models (those with a sibling `*-vocab.txt`), and a virtual `gemini-nano-aicore` entry. |
| `POST /v1/chat/completions` | Gated by API key. Routes to AICore when `model == "gemini-nano-aicore"`, otherwise to LiteRT-LM. Honors `stream`, `temperature`, `top_k`, `max_tokens`, `session_id`, `tools`, `tool_choice`, and OpenAI-shaped multimodal `content` arrays. AICore path is stateless (no `session_id` reuse). Response headers include `X-Queue-Position`, `X-Queue-Depth`, `X-Estimated-Wait-Ms`, `X-Request-Id`, `X-Client-Id`. |
| `POST /v1/embeddings` | OpenAI-compatible. ONNX Runtime + WordPiece tokenizer. Single or batched `input`. |
| `POST /v1/documents` · `GET /v1/documents` · `DELETE /v1/documents/{id}` | RAG document store backed by ObjectBox (HNSW, dim 384). |
| `POST /v1/search` | Top-K vector search over the document store. |
| `GET /v1/tenants` · `DELETE /v1/tenants/{tenantId}` | Tenant isolation for multi-app document stores. |

## Build & install

```bash
cd localllm-android
./gradlew assembleDebug
# APK at app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`local.properties` needs `sdk.dir=...` pointing at your Android SDK
(omitted on CI — `ANDROID_HOME` is set by the GitHub Actions runner).

Min SDK 29, target 34, compile 35. Built with Kotlin 2.2.21, AGP 8.7.3,
Ktor 3.4.3, Compose BOM 2024.09.02, LiteRT-LM 0.12.0, ML Kit GenAI
Prompt 1.0.0-beta2 (AICore), ONNX Runtime 1.18.0 (embeddings),
ObjectBox 4.0.3 (vector store).

CI: `.github/workflows/build.yml` runs `./gradlew lint testDebugUnitTest
assembleDebug` on every push and PR against `main`, with Gradle caching
keyed on `libs.versions.toml` + `**/*.gradle.kts`. The debug APK is
uploaded as a workflow artifact on each successful run.

## Pre-built APK

The latest signed-with-debug-key APK ships as a GitHub Release asset on
this repo — see the Releases page and download `app-debug.apk`. Install
with `adb install -r app-debug.apk` or by transferring the file to your
device and opening it (you'll need to allow "install from unknown
sources" for your file manager).

## Notes & internals

- **Inference is serialized** via a `Mutex`: only one request at a time per
  device, even if multiple clients connect. LiteRT-LM is single-tenant in
  practice.
- **No chat templating in our code.** LiteRT-LM reads the prompt template
  from the `.litertlm` bundle's metadata — we just pass `Contents.of(text)`
  and the engine handles the `<start_of_turn>` markers, BOS/EOS tokens, etc.
- **Per-request `temperature` and `top_k`** are applied via
  `ConversationConfig.SamplerConfig` — the engine itself doesn't accept
  them.
- The notification's **Stop** button cleanly tears down the server, cancels
  the service coroutine scope, releases the wake lock, and evicts cached
  engines + conversations.

## Roadmap

- **Feature-sliced split of `LLMServerService.kt`** — the file is still a
  ~2.3k-line monolith mixing Ktor lifecycle, route handlers, engine cache,
  session resolution, and the AICore branch. Splitting into `server/`,
  `server/routes/`, `inference/litert/`, `inference/aicore/` packages is
  planned but hasn't landed.
- **Structured AICore error envelopes** — today the AICore branch surfaces
  `IllegalStateException` with a free-form message ("AICore is downloadable…")
  through the generic `ErrorResponse` shape. A first-class envelope with
  machine-readable codes (`AICORE_DOWNLOADABLE`, `AICORE_DOWNLOADING`,
  `AICORE_BACKGROUND_BLOCKED`, …) + `next_steps[]` would let clients react
  instead of regex-matching error strings.
- **Audio `content` blocks** — image input lands today; LiteRT-LM also
  supports `Content.AudioBytes` which the OpenAI-compat layer doesn't
  surface yet.
- **Persistent log buffer + observability** — `LogManager` is in-memory
  only. A persistent ring buffer plus an optional Sentry/Crashlytics
  integration would help diagnose field issues.
- **Multi-process isolation** — `LLMServerService` runs in the main app
  process today; moving it to `:server` would survive Activity-process
  kills but requires IPC for the cross-process singletons
  (`ServerState`, `RequestTracker`, `LogManager`).
