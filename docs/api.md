# HTTP API

Everything except `/health` is gated by the optional API key configured
in **Settings → API key**. When the key is empty (the default), all
endpoints are open.

| Path | Auth | Notes |
|---|---|---|
| [`GET /health`](#health) | always open | Liveness + LiteRT engine attempt chain. |
| [`GET /v1/models`](#models) | optional bearer | Lists `.litertlm` files, ONNX embedding models, and the virtual `gemini-nano-aicore` entry. |
| [`POST /v1/chat/completions`](#chat-completions) | optional bearer | OpenAI-style. Routes to AICore for `gemini-nano-aicore`, otherwise to LiteRT-LM. |
| `POST /v1/embeddings` | optional bearer | ONNX-backed embeddings. |
| `POST /v1/documents` · `GET /v1/documents` · `DELETE /v1/documents/{id}` | optional bearer | RAG document store (ObjectBox HNSW, dim 384). |
| `POST /v1/search` | optional bearer | Top-K vector search. |
| `GET /v1/tenants` · `DELETE /v1/tenants/{tenantId}` | optional bearer | Tenant isolation. |

## `GET /health` { #health }

Never gated. Use this for liveness / readiness probes.

```bash
curl -s http://localhost:8099/health
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
      "key": "gemma-4-e2b_model_AUTO",
      "backend": "CPU",
      "attempts": [
        {"backend": "NPU", "result": "failed: TF_LITE_AUX not found in the model", "duration_ms": 5394},
        {"backend": "GPU", "result": "skipped: known SIGSEGV on Tensor", "duration_ms": 0},
        {"backend": "CPU", "result": "ok", "duration_ms": 3168}
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

## `GET /v1/models` { #models }

Lists `.litertlm` LLMs on disk, ONNX embedding models (with a
sibling `*-vocab.txt`), and the always-present virtual AICore entry.

```bash
curl -s -H "Authorization: Bearer $LLM_KEY" \
  http://localhost:8099/v1/models
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
