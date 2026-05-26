# LocalLLM Edge Server

**An on-device, OpenAI-compatible LLM HTTP server for Android.** Two
engines under one OpenAI Chat Completions API: Google's **AICore (Gemini
Nano)** via ML Kit GenAI on Pixel-class devices, and any **LiteRT-LM**
`.litertlm` bundle (Gemma 4 family + seven NPU-compiled Gemma 3 1B
SoC variants). No cloud, no remote API key, no data leaves the device.

!!! tip "Why this exists"
    Most apps already talk to LLMs over HTTP. The moment you move
    inference on-device, that contract breaks — every app embeds its
    own runtime, its own weights, its own engine. Three apps that
    each want a 2 GB model want 6 GB of RAM and three threads
    fighting for the same accelerator. This project is the one
    place on the device where LLMs actually run: apps keep speaking
    HTTP, the server owns the model lifecycle, queue, KV cache,
    rate limits, and metrics.

<div class="grid cards" markdown>

-   :material-rocket-launch:{ .lg .middle } __Open-API drop-in__

    ---

    Any OpenAI client library can talk to it. Streaming SSE,
    `session_id`-based KV cache reuse, per-request `temperature` /
    `top_k` / `max_tokens` — all wire-compatible with
    `POST /v1/chat/completions`.

    [:octicons-arrow-right-24: HTTP API](api.md)

-   :material-cpu-64-bit:{ .lg .middle } __Two engines, one API__

    ---

    **AICore (Gemini Nano)** via `com.google.mlkit:genai-prompt:1.0.0-beta2`
    is the default — `gemini-nano-aicore` is `Settings.DEFAULT_MODEL_ID`.
    **LiteRT-LM** (`com.google.ai.edge.litertlm:litertlm-android:0.12.0`)
    is the alternative for offline weights, custom `.litertlm` bundles, or
    NPU-compiled SoC variants. Each catalog entry declares its `Backend`
    (`AICORE` / `LITERT_CPU` / `LITERT_GPU` / `LITERT_NPU`) — no fallback
    chain. `GET /health` exposes the live AICore status and the cached
    LiteRT engines.

    [:octicons-arrow-right-24: Architecture](architecture.md)

-   :material-android:{ .lg .middle } __Production-leaning Android app__

    ---

    Foreground service with `specialUse` declaration, SHA-256 download
    verification, signed-bearer-token API key, partial wake-lock under
    inference, idle-eviction of GB-sized engines, atomic queue cap with
    `429 Retry-After`, SSE error chunks (no silent drops).

    [:octicons-arrow-right-24: Getting started](getting-started.md)

-   :material-code-tags:{ .lg .middle } __Hackable__

    ---

    ~2k lines of Kotlin + Compose on top of Ktor 3. Single Gradle
    module, single test target, CI gating with GitHub Actions. New
    catalog entries are one struct + a SHA-256.

    [:octicons-arrow-right-24: Development](development.md)

</div>

## What it looks like

<div class="grid" markdown>

![Catalog tab](screenshots/catalog.png){ width=250 }
![Chat tab](screenshots/chat.png){ width=250 }
![Dashboard tab](screenshots/dashboard.png){ width=250 }
![Console tab](screenshots/console.png){ width=250 }
![Settings tab](screenshots/settings.png){ width=250 }

</div>

## Quick demo

```bash
# 1. Install
adb install -r app-debug.apk

# 2. Forward the port to your laptop (or use the LAN IP from the app header)
adb forward tcp:8080 tcp:8080

# 3a. AICore (Gemini Nano) — no download, requires a supported Pixel.
#     Keep the LocalLLM app in the foreground while the request is in
#     flight (AICore returns ErrorCode 30 if backgrounded).
curl http://localhost:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{
    "model": "gemini-nano-aicore",
    "messages": [{"role": "user", "content": "Say hi in one word."}]
  }'

# 3b. Or, after tapping "Download" on Gemma 4 E2B IT (~2.6 GB) in the
#     Catalog tab, use the LiteRT-LM path — no foreground constraint.
curl http://localhost:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{
    "model": "gemma-4-e2b",
    "messages": [{"role": "user", "content": "Say hi in one word."}]
  }'
```

## Why this exists

There's no public Android library that exposes Gemini Nano *and*
Gemma 4 behind one OpenAI-compatible HTTP surface. AICore is gated
behind ML Kit GenAI's Prompt API; Gemma 4 ships only as `.litertlm`
bundles via Google's LiteRT-LM runtime. This app stitches both into
the same `/v1/chat/completions` endpoint so every app on the device
(or every device on your LAN) can pick the right engine per request
without shipping its own copy of either runtime.

It's the same idea as running [Ollama](https://ollama.ai) on a laptop,
except the daemon runs in your pocket.

## License

[Apache 2.0](https://github.com/mlnomadpy/localllm/blob/main/LICENSE).
The Gemma model weights themselves are governed by [Google's Gemma
Terms of Use](https://ai.google.dev/gemma/terms).
