# HTTP API

Everything except `/health` and `/metrics` is gated by the optional
API key configured in **Settings → API key**. When the key is empty
(the default), all endpoints are open.

| Path | Auth | Notes |
|---|---|---|
| [`GET /health`](#health) | always open | Liveness + cached-engine attempts + AICore status block. |
| [`POST /health/warm`](#warm) | optional bearer | Force-warm an engine; waits for first-token-ready (60s timeout). |
| [`GET /v1/models`](#models) | optional bearer | Lists `.litertlm` files, ONNX embedding models, and the virtual `gemini-nano-aicore` entry. |
| [`POST /v1/chat/completions`](#chat-completions) | optional bearer | OpenAI-style. Routes to AICore for `gemini-nano-aicore`, otherwise to LiteRT-LM. |
| `POST /v1/embeddings` | optional bearer | ONNX-backed embeddings. Single or batched `input`. |
| `POST /v1/documents` · `GET /v1/documents` · `DELETE /v1/documents/{id}` | optional bearer | RAG document store (ObjectBox HNSW, dim 384). |
| `POST /v1/search` | optional bearer | Top-K vector search over the document store. |
| `GET /v1/tenants` · `DELETE /v1/tenants/{tenantId}` | optional bearer | Tenant isolation. |
| [`GET /v1/aicore/status`](#aicore-status) | optional bearer | Detailed Gemini Nano readiness probe. `?probe=all` for per-config breakdown. |
| [`POST/GET /v1/aicore/benchmark`](#aicore-benchmark) | optional bearer | TTFT + tokens/sec + total-ms speed test. |
| [`GET /metrics`](#metrics) | always open | Prometheus exposition (counters + gauges, per-engine + per-client). |

## Response headers

`POST /v1/chat/completions` emits these headers **before** the first
SSE byte, so streaming clients can show queue position up front:

| Header | Meaning |
|---|---|
| `X-Request-Id` | Unique 64-bit counter assigned by `RequestTracker`. |
| `X-Client-Id` | Resolved from `User-Agent` (or `"anonymous"`). |
| `X-Queue-Position` | 1-indexed position at admission. |
| `X-Queue-Depth` | Total queued at admission. |
| `X-Estimated-Wait-Ms` | `(position - 1) × avg_latency_ms` from recent history. |
| `Retry-After` | Seconds to wait — set on `429`. |
| `X-RateLimit-Client` | Client UA that hit the bucket — set on `429` from rate limiter. |

RAG routes emit `X-Tenant-Id` so clients can confirm the resolved
tenant from their `X-Client-Id` / `User-Agent` headers.

## `GET /health` { #health }

Never gated. Use this for liveness / readiness probes.

```bash
curl -s http://localhost:8080/health
```

```json
{
  "status": "ok",
  "service": "localllm-android",
  "version": "1.0",
  "queue_depth": 0,
  "engines_loaded": 1,
  "engines": [
    {
      "key": "gemma-4-e2b_model_LITERT_CPU",
      "backend": "LITERT_CPU",
      "attempts": [
        {"backend": "NPU-primer", "result": "expected-fail: no vendor delegate", "duration_ms": 312},
        {"backend": "LITERT_CPU", "result": "ok", "duration_ms": 3168}
      ]
    }
  ]
}
```

- `queue_depth` — requests currently queued behind the inference
  mutex.
- `engines_loaded` — LiteRT engines in the LRU cache. AICore requests
  do **not** appear here; they run inside the AICore system service.
- `engines[].key` — engine cache key in the shape
  `<model>_<maxTokens|"model">_<backend>`.
- `engines[].backend` — the backend declared for this model in the
  catalog (`LITERT_CPU`, `LITERT_GPU`, or `LITERT_NPU`). Each engine
  records the single init attempt that built it; no AUTO chain.
- `engines[].attempts` — the init record for the declared backend with
  `result` (`ok` / `failed: …`) and `duration_ms`. Single entry now —
  the AUTO chain was removed.
- `aicore` — readiness of Gemini Nano: `{status_code, status,
  model_id, is_default: true}`. On a device where the AICore probe
  throws (e.g. service not installed), `status_code` is `null` and
  `error` carries the SDK message.

## `POST /health/warm` { #warm }

Force-warm an engine and wait until it's ready to emit the first
token. Useful before showing a "ready" UI in a sibling app: the
endpoint blocks for up to 60 seconds while LiteRT-LM links the JNI
runtime or AICore triggers a model download.

```bash
curl -s -X POST "http://localhost:8080/health/warm?model=gemma-4-e2b" \
  -H "Authorization: Bearer $LLM_KEY"
```

Query parameters:

- `model` (optional) — model id to warm. Defaults to
  `Settings.selectedModelId` (`gemini-nano-aicore`).

Responses:

| Status | Body |
|---|---|
| `200` | `{"model": "...", "status": "warm", "engine_loaded": true, "ms": 1500}` |
| `503` | `{"status": "aicore_not_ready", "aicore_status": "downloadable", "ms": …}` |
| `504` | `{"status": "timeout", "ms": 60000}` |
| `503` | `{"status": "error", "error": "...", "ms": …}` |

The warm-up uses its own mutex — it does **not** block concurrent
chat requests. For LiteRT models the engine runs a 1-token
generation to ensure the JNI side-effects have settled (Tensor SoCs
need this); for AICore the readiness probe runs.

## `GET /v1/models` { #models }

Lists `.litertlm` LLMs on disk, ONNX embedding models (with a
sibling `*-vocab.txt`), and the always-present virtual AICore entry.

```bash
curl -s -H "Authorization: Bearer $LLM_KEY" \
  http://localhost:8080/v1/models
```

```json
{
  "object": "list",
  "data": [
    { "id": "gemma-4-e2b",         "object": "model", "created": 1778610084, "owned_by": "local" },
    { "id": "gemini-nano-aicore",  "object": "model", "created": 1778610090, "owned_by": "google-aicore" }
  ]
}
```

For LiteRT-LM entries, `id` is the filename with `.litertlm` stripped.
The `gemini-nano-aicore` entry is listed unconditionally so clients
can probe; the chat handler surfaces a clean error if AICore isn't
installed or the model isn't downloaded yet.

## `POST /v1/chat/completions` { #chat-completions }

OpenAI-compatible chat completion. Streaming and blocking.

**Routing.** When `model == "gemini-nano-aicore"` the request bypasses
the LiteRT engine cache and the inference mutex — AICore runs inside
the system service and handles its own serialization. Every other
model id is resolved against `.litertlm` files on disk and goes
through the LiteRT-LM path. The two paths differ in three places that
clients should know about:

| | AICore (`gemini-nano-aicore`) | LiteRT-LM (e.g. `gemma-4-e2b`) |
|---|---|---|
| Backend selection | Decided by AICore. No surface. | Declared per-model in the catalog (`Backend.LITERT_CPU` / `_GPU` / `_NPU`). No fallback chain. |
| `session_id` (KV reuse) | Ignored. Stateless. History is flattened into one prompt. | Honored — see [multi-turn](#multi-turn-with-session_id). |
| App lifecycle constraint | **Foreground only.** Backgrounded calls fail with ErrorCode 30. | None. |
| `tools` / `tool_choice` | Not supported by the SDK. | Honored. |
| Multimodal `content` parts | Text only. | Text + `image_url` parts. |

### Request

| Field | Type | Default | Notes |
|---|---|---|---|
| `model` | string | required | An `id` from `GET /v1/models`. |
| `messages` | array | required | Each item is `{role, content}` where `role` is `"system"`, `"user"`, or `"assistant"`. The first system message becomes `ConversationConfig.systemInstruction`; remaining messages are fed as conversation history. The last message must be `user`. |
| `stream` | bool | `false` | Server-Sent Events when true. |
| `session_id` | string | `null` | Stable opaque ID for KV-cache reuse across turns. See [multi-turn](#multi-turn-with-session_id). |
| `temperature` | float | server default | Per-request sampler temperature. |
| `top_k` | int | server default | Per-request sampler top-k. |
| `max_tokens` | int | model default | Per-request total-token budget (input + output). Omit to use the model's compiled budget — overriding it down can trigger `DYNAMIC_UPDATE_SLICE` shape mismatches on big-context Gemma 4 weights. |

### Blocking response

```json
{
  "id": "chatcmpl-1",
  "object": "chat.completion",
  "created": 1778611764,
  "model": "gemma-4-e2b",
  "choices": [
    {
      "index": 0,
      "message": { "role": "assistant", "content": "Hi! How can I help?" },
      "finish_reason": "stop"
    }
  ]
}
```

### Streaming response

SSE frames are emitted as the model generates tokens. The first frame
contains `delta.role = "assistant"`; subsequent frames carry
`delta.content`; the final frame carries `finish_reason: "stop"`. The
stream terminates with `data: [DONE]`.

```
data: {"id":"chatcmpl-2","object":"chat.completion.chunk","created":1778610331,"model":"gemma-4-e2b","choices":[{"delta":{"role":"assistant"},"index":0}]}

data: {"id":"chatcmpl-2","object":"chat.completion.chunk","created":1778610333,"model":"gemma-4-e2b","choices":[{"delta":{"content":"Hi"},"index":0}]}

data: {"id":"chatcmpl-2","object":"chat.completion.chunk","created":1778610334,"model":"gemma-4-e2b","choices":[{"delta":{"content":"!"},"index":0}]}

data: {"id":"chatcmpl-2","object":"chat.completion.chunk","created":1778610335,"model":"gemma-4-e2b","choices":[{"delta":{},"finish_reason":"stop","index":0}]}

data: [DONE]
```

A heartbeat comment (`: ka\n\n`) is emitted every 10 seconds so
intermediaries don't kill long prefill times.

### Error responses

| Code | Shape | Meaning |
|---|---|---|
| `400` | `ErrorResponse` | Malformed JSON body. |
| `401` | `ErrorResponse` | Missing / invalid bearer token. |
| `408` | `ErrorResponse` | Request timeout (configurable in Settings). |
| `413` | `ErrorResponse` | Prompt or body exceeds the configured cap. |
| `429` | `ErrorResponse` + `Retry-After: 5` | Queue full (default 8 in flight). |
| `500` | `ErrorResponse` | Server / engine error. |

`ErrorResponse` schema:

```json
{ "error": { "message": "Inference timeout", "type": "timeout", "code": 408 } }
```

Errors **mid-stream** are delivered as a final SSE chunk + the `[DONE]`
sentinel — never as a silently-closed connection:

```
data: {"error":{"message":"Inference timeout","type":"timeout","code":408}}

data: [DONE]
```

## Multi-turn with `session_id`

Pass a stable `session_id` and the server caches the underlying
`Conversation` object across calls. Follow-up requests only need to
re-feed *new* user turns; assistant turns echoed by the client are
already in the model's KV cache and are skipped.

```jsonc title="Turn 1"
POST /v1/chat/completions
{
  "model": "gemma-4-e2b",
  "session_id": "alice-2026-05-10",
  "messages": [{"role": "user", "content": "Hi"}]
}
```

```jsonc title="Turn 2 — same session_id, full history echoed (OpenAI convention)"
POST /v1/chat/completions
{
  "model": "gemma-4-e2b",
  "session_id": "alice-2026-05-10",
  "messages": [
    {"role": "user", "content": "Hi"},
    {"role": "assistant", "content": "Hello! How can I help?"},
    {"role": "user", "content": "Tell me about kernels."}
  ]
}
```

Server-side rules:

1. **Empty / missing `session_id`** → fresh `Conversation` every
   request. Default.
2. **Non-empty `session_id`** → cached `Conversation`. The server
   validates the replayed prefix via a stable hash of all
   `messages[0..seenCount]`. Mismatch → rebuild from scratch.
3. **Sampling parameter change mid-session** (`temperature` /
   `top_k`) → rebuild. Those are conversation-construction
   parameters in LiteRT-LM's `SamplerConfig`.
4. **Cache size** is 4. Oldest evicted. Conversations are also
   evicted together with their parent engine when the engine is
   unloaded.

On a 10-turn conversation, request N pays only the cost of prefilling
turn N's new user message, not the entire history each time.

## Structured error envelopes

Chat completion failures return a `RichErrorResponse` with a
machine-readable code so clients can react instead of regex-matching
the message:

```json
{
  "error": {
    "message": "Gemini Nano is downloading on this device.",
    "type": "aicore_unavailable",
    "code": "AICORE_DOWNLOADING",
    "aicore_status": "downloading",
    "actionable": true,
    "next_steps": ["Wait for status: Available", "Retry"]
  }
}
```

| `code` | HTTP | `actionable` | Meaning |
|---|:-:|:-:|---|
| `AICORE_DOWNLOADABLE` | 503 | ✅ | Gemini Nano can be downloaded; open Models → tap Download. |
| `AICORE_DOWNLOADING` | 425 | ✅ | Download in progress; retry later. |
| `AICORE_UNAVAILABLE` | 503 | ❌ | Not available on this device; switch to a LiteRT model. |
| `AICORE_BACKGROUND_BLOCKED` | 403 | ✅ | Host app was backgrounded mid-request (ErrorCode 30). |
| `AICORE_RUNTIME_ERROR` | 500 | ❌ | Generic AICore runtime failure. |
| `LITERT_INIT_FAILED` | 503 | ✅ | Engine init failed — check the file, SoC match, vendor delegate. |
| `AICORE_UNKNOWN` | 503 | ❌ | Unrecognised AICore status code. |

## `GET /v1/aicore/status` { #aicore-status }

Detailed readiness probe for Gemini Nano. Surfaces the SDK status
code, a human-readable label, and the `Build.SOC_MODEL` /
`Build.DEVICE` / `Build.MANUFACTURER` so clients can decide whether
to even surface AICore as an option.

```bash
curl -s "http://localhost:8080/v1/aicore/status" -H "Authorization: Bearer $LLM_KEY"
```

```json
{
  "model_id": "gemini-nano-aicore",
  "status_code": 3,
  "status": "available",
  "available": true,
  "soc_model": "Tensor G5",
  "device": "frankel",
  "manufacturer": "Google"
}
```

`status_code`: `3 = available`, `2 = downloadable`, `1 = downloading`,
`0 = unavailable`.

Add `?probe=all` to get a per-`(releaseStage × preference)`
breakdown — useful on Pixel 10 where `STABLE` may report
unavailable but `PREVIEW/FAST` works.

## `POST/GET /v1/aicore/benchmark` { #aicore-benchmark }

Runs a TTFT + tokens/sec speed test against AICore. `POST` runs a
fresh benchmark; `GET` serves the last cached result (404 until
the first run).

```bash
curl -s -X POST http://localhost:8080/v1/aicore/benchmark \
  -H "Authorization: Bearer $LLM_KEY" \
  -H "Content-Type: application/json" \
  -d '{"prompts": ["Say hi.", "Count to ten."], "warmup": 1}'
```

`prompts` and `warmup` are both optional (sensible defaults).
`warmup` is clamped to `[0, 5]`.

```json
{
  "model_id": "gemini-nano-aicore",
  "status": "available",
  "warmup_runs": 1,
  "results": [
    { "prompt": "Say hi.",        "output_tokens": 5,  "inference_ms": 124, "tokens_per_sec": 40.3 },
    { "prompt": "Count to ten.",  "output_tokens": 22, "inference_ms": 612, "tokens_per_sec": 35.9 }
  ],
  "total_tokens": 27,
  "total_ms": 736,
  "avg_tokens_per_sec": 38.1
}
```

Refuses (503) when AICore isn't `STATUS_AVAILABLE`. The Dashboard
tab's *AICore Benchmark* card calls this endpoint and renders the
last result inline.

## `GET /metrics` { #metrics }

Prometheus text exposition for the on-device server. Never gated.

```bash
curl -s http://localhost:8080/metrics
```

```
# TYPE localllm_requests_total counter
localllm_requests_total 42
# TYPE localllm_requests_completed_total counter
localllm_requests_completed_total 40
# TYPE localllm_inference_seconds_avg gauge
localllm_inference_seconds_avg 3.1375
# TYPE localllm_queue_depth gauge
localllm_queue_depth 2
# TYPE localllm_inflight gauge
localllm_inflight 1
# TYPE localllm_engine_loaded gauge
localllm_engine_loaded{key="gemma-4-e2b_model_LITERT_CPU",backend="LITERT_CPU"} 1
# TYPE localllm_client_requests_total counter
localllm_client_requests_total{client="MyApp/1.0"} 35
```

Metrics emitted:

- **Counters:** `localllm_requests_total`, `localllm_requests_completed_total`,
  `localllm_requests_errored_total`, `localllm_requests_cancelled_total`,
  `localllm_stream_chunks_total`, `localllm_inference_seconds_total`,
  `localllm_client_requests_total{client}`.
- **Gauges:** `localllm_inference_seconds_avg`, `localllm_chunks_per_second_avg`,
  `localllm_queue_depth`, `localllm_inflight`, `localllm_engines_loaded`,
  `localllm_engine_loaded{key,backend}`,
  `localllm_client_inference_seconds_avg{client}`.

Point Grafana Agent / OpenTelemetry Collector at it like any other
Prometheus target — Tailscale or `adb forward` is the easy way to
get to it from off-device.

## Service discovery (mDNS / NSD)

When **Settings → Bind to LAN** is on, the service advertises itself
via `android.net.nsd`:

| | |
|---|---|
| Service type | `_localllm._tcp.` |
| Service name | `LocalLLM` |
| TXT records | `api=openai-compat`, `path=/v1`, `health=/health` |

Loopback-only binds skip the advert. Any zero-conf browser
(`dns-sd -B _localllm._tcp .` on macOS, Bonjour Browser, Avahi)
discovers it.

## What's not implemented (yet)

The OpenAI-compat surface is intentionally partial. Missing pieces,
in roughly the order they'll land:

- `logprobs` / `top_logprobs` — LiteRT-LM exposes them but they're
  not wired through.
- `n` > 1 — single completion per request.
- `stop` sequences — parsed but ignored.
- `tools` / function calling — LiteRT-LM supports it natively
  (`ConversationConfig.tools`), the HTTP layer doesn't.
- Vision / audio `content` blocks — LiteRT-LM `Content.ImageBytes`
  and `Content.AudioBytes` exist but the route only extracts
  `Content.Text`.

If you need any of these, [open an issue](https://github.com/mlnomadpy/localllm/issues)
or jump to [development](development.md).
