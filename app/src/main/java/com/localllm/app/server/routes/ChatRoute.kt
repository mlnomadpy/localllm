package com.localllm.app.server.routes

import android.content.Context
import com.google.ai.edge.litertlm.Message as LlmMessage
import com.google.ai.edge.litertlm.ToolCall
import com.google.gson.Gson
import com.localllm.app.ChatRequest
import com.localllm.app.ChatResponse
import com.localllm.app.Choice
import com.localllm.app.ContentPart
import com.localllm.app.ErrorDetails
import com.localllm.app.ErrorResponse
import com.localllm.app.LogManager
import com.localllm.app.Message
import com.localllm.app.RateLimiter
import com.localllm.app.RequestTracker
import com.localllm.app.RichErrorDetails
import com.localllm.app.RichErrorResponse
import com.localllm.app.Settings
import com.localllm.app.StreamChoice
import com.localllm.app.StreamDelta
import com.localllm.app.StreamResponse
import com.localllm.app.ToolCallApi
import com.localllm.app.ToolCallFunction
import com.localllm.app.contentParts
import com.localllm.app.contentString
import com.localllm.app.inference.AiCoreNotReadyException
import com.localllm.app.inference.EngineRegistry
import com.localllm.app.inference.aicore.AICoreEngine
import com.localllm.app.inference.litert.LlmMessageConverter
import com.localllm.app.inference.litert.SessionManager
import com.localllm.app.server.auth.authorize
import com.localllm.app.stringContent
import com.localllm.app.textChars
import io.ktor.http.CacheControl
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.cacheControl
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeStringUtf8
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * `POST /v1/chat/completions` — OpenAI-compatible. The single biggest route
 * in the codebase: handles request validation, rate limiting, queue depth,
 * engine acquisition (catalog-driven backend selection), session reuse for
 * LiteRT, the AICore bypass, streaming SSE, non-streaming JSON, tool-call
 * round-trips, and timeout / cancellation cleanup.
 *
 * The catalog-declared backend is the only signal — there is no AUTO chain
 * here anymore. AICore models bypass the inference mutex entirely (the
 * system service handles serialization); LiteRT models hold the mutex
 * across `runInference*` so we never have two engines pumping the JNI
 * runtime at once.
 *
 * If `req.model` is absent or empty, the handler falls back to the
 * persisted [Settings.selectedModelId] (which defaults to AICore).
 */
fun Route.chatRoute(
    appContext: Context,
    engineRegistry: EngineRegistry,
    sessionManager: SessionManager,
    rateLimiter: RateLimiter,
    inferenceMutex: Mutex,
    serviceScope: CoroutineScope,
    lastActivityAt: AtomicLong,
    acquireWakeLock: suspend (timeoutMs: Long, block: suspend () -> Unit) -> Unit,
) {
    val gson = Gson()
    post("/v1/chat/completions") {
        if (!authorize(call, appContext)) return@post
        lastActivityAt.set(System.currentTimeMillis())

        // Per-client rate limit, keyed on User-Agent. rate=0 disables.
        val clientId = call.request.headers["User-Agent"]?.takeIf { it.isNotBlank() } ?: "anonymous"
        val rate = Settings.rateLimitPerSec(appContext)
        if (rate > 0.0) {
            rateLimiter.ratePerSec = rate
            rateLimiter.burst = Settings.rateLimitBurst(appContext)
            val retryAfter = rateLimiter.tryAcquire(clientId)
            if (retryAfter != null) {
                call.response.header("Retry-After", retryAfter.toString())
                call.response.header("X-RateLimit-Client", clientId)
                call.respond(
                    HttpStatusCode.TooManyRequests,
                    ErrorResponse(ErrorDetails(
                        message = "Rate limit for client '$clientId' exhausted; retry in ${retryAfter}s.",
                        type = "rate_limit_error",
                        code = 429,
                    )),
                )
                return@post
            }
        }

        // Body-size guard. ~2 bytes per char covers JSON overhead with margin.
        val maxChars = Settings.maxPromptChars(appContext)
        val bodyCap = maxChars.toLong() * 2L + 8_192L
        val contentLength = call.request.headers["Content-Length"]?.toLongOrNull()
        if (contentLength != null && contentLength > bodyCap) {
            call.respond(
                HttpStatusCode.PayloadTooLarge,
                ErrorResponse(ErrorDetails(
                    message = "Request body of $contentLength bytes exceeds cap of $bodyCap",
                    type = "invalid_request_error",
                    code = 413,
                )),
            )
            return@post
        }

        val rawReq = try {
            call.receive<ChatRequest>()
        } catch (e: Exception) {
            LogManager.e("ChatRoute", "Failed to parse ChatRequest body", e)
            val rootCause = generateSequence(e as Throwable?) { it.cause }.lastOrNull() ?: e
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse(ErrorDetails(
                    message = "Invalid JSON body: ${rootCause.javaClass.simpleName}: ${rootCause.message ?: e.message}",
                    type = "invalid_request_error",
                    code = 400,
                )),
            )
            return@post
        }

        // Default model resolution: missing/empty `model` falls back to the
        // selected model id (which defaults to AICore). The AUTO fallback
        // used to pick the first downloaded `.litertlm`; that selection now
        // belongs to the user (via Settings) and is explicit.
        val req = if (rawReq.model.isBlank()) {
            rawReq.copy(model = Settings.selectedModelId(appContext))
        } else rawReq

        val promptChars = req.messages.sumOf { it.textChars() }
        if (promptChars > maxChars) {
            call.respond(
                HttpStatusCode.PayloadTooLarge,
                ErrorResponse(ErrorDetails(
                    message = "Prompt of $promptChars chars exceeds limit of $maxChars",
                    type = "invalid_request_error",
                    code = 413,
                )),
            )
            return@post
        }

        val maxDepth = Settings.maxQueueDepth(appContext)
        val entry = RequestTracker.tryEnqueue(
            model = req.model,
            stream = req.stream,
            messageCount = req.messages.size,
            promptChars = promptChars,
            maxDepth = maxDepth,
            client = clientId,
        )
        if (entry == null) {
            call.response.header("Retry-After", "5")
            call.respond(
                HttpStatusCode.TooManyRequests,
                ErrorResponse(ErrorDetails(
                    message = "Queue full ($maxDepth in flight). Retry shortly.",
                    type = "rate_limit_error",
                    code = 429,
                )),
            )
            return@post
        }

        val queueDepth = RequestTracker.queue.value.size
        val queuePosition = RequestTracker.queue.value.indexOfFirst { it.id == entry.id } + 1
        val avgInfMs = RequestTracker.stats.value.avgLatencyMs
        val estimatedWaitMs = (queuePosition - 1).coerceAtLeast(0) * avgInfMs
        call.response.header("X-Queue-Position", queuePosition.toString())
        call.response.header("X-Queue-Depth", queueDepth.toString())
        call.response.header("X-Estimated-Wait-Ms", estimatedWaitMs.toString())
        call.response.header("X-Request-Id", entry.id)
        call.response.header("X-Client-Id", clientId)

        val remoteIp = call.request.local.remoteHost
        val ua = clientId
        val timeoutMs = Settings.requestTimeoutMs(appContext)

        var resolved: SessionManager.Resolved? = null
        var inferenceOk = false
        var streamWriter: ByteWriteChannel? = null
        try {
            LogManager.i(
                "ChatRoute",
                "Request #${entry.id} from $remoteIp [$ua]: model=${req.model}, stream=${req.stream}, msgs=${req.messages.size}, chars=$promptChars, session=${req.sessionId?.ifEmpty { null } ?: "(stateless)"}",
            )

            val responseId = "chatcmpl-${entry.id}"
            val temp = req.temperature ?: Settings.temperature(appContext)
            val topK = req.topK ?: Settings.topK(appContext)

            val acquired = try {
                engineRegistry.acquire(req.model, req.maxTokens)
            } catch (e: Throwable) {
                LogManager.e("ChatRoute", "Engine acquire failed for ${req.model}", e)
                RequestTracker.markCompleted(entry.id, error = "engine_acquire: ${e.message ?: e.javaClass.simpleName}")
                val (httpStatus, details) = liteRtAcquireEnvelope(req.model, e)
                call.respond(httpStatus, RichErrorResponse(details))
                return@post
            }
            when (acquired) {
                is EngineRegistry.AcquiredEngine.AiCore -> {
                    // AICore preflight — surfaces a structured envelope
                    // before we commit response headers / SSE prelude.
                    try {
                        engineRegistry.ensureAiCoreReady()
                    } catch (notReady: AiCoreNotReadyException) {
                        val (httpStatus, details) = aicoreNotReadyEnvelope(notReady.statusCode, notReady.probeError)
                        call.respond(httpStatus, RichErrorResponse(details))
                        RequestTracker.markCompleted(entry.id, error = "aicore_${details.code}")
                        return@post
                    }
                    val flatPrompt = flattenForAICore(req.messages)
                    if (req.stream) {
                        call.response.cacheControl(CacheControl.NoCache(null))
                        call.respondBytesWriter(contentType = ContentType.Text.EventStream) {
                            streamWriter = this@respondBytesWriter
                            withTimeout(timeoutMs) {
                                RequestTracker.markStarted(entry.id)
                                runAICoreStreaming(
                                    writer = this@respondBytesWriter,
                                    prompt = flatPrompt,
                                    temperature = temp,
                                    topK = topK,
                                    maxOutputTokens = req.maxTokens,
                                    responseId = responseId,
                                    modelName = req.model,
                                    requestEntryId = entry.id,
                                    gson = gson,
                                    onChunk = { delta -> RequestTracker.recordChunk(entry.id, delta) },
                                )
                            }
                        }
                    } else {
                        val text = withTimeout(timeoutMs) {
                            RequestTracker.markStarted(entry.id)
                            AICoreEngine.complete(
                                prompt = flatPrompt,
                                temperature = temp,
                                topK = topK,
                                maxOutputTokens = req.maxTokens,
                            )
                        }
                        RequestTracker.recordChunk(entry.id, text)
                        call.respond(ChatResponse(
                            id = responseId,
                            `object` = "chat.completion",
                            created = System.currentTimeMillis() / 1000,
                            model = req.model,
                            choices = listOf(Choice(
                                index = 0,
                                message = Message(role = "assistant", content = stringContent(text)),
                                finishReason = "stop",
                            )),
                        ))
                    }
                    inferenceOk = true
                    RequestTracker.markCompleted(entry.id)
                    lastActivityAt.set(System.currentTimeMillis())
                    return@post
                }
                is EngineRegistry.AcquiredEngine.LiteRt -> {
                    val resolvedLocal = sessionManager.resolve(req, acquired, temp, topK)
                    resolved = resolvedLocal
                    val needWakeLock = Settings.keepAwake(appContext)

                    if (req.stream) {
                        call.response.cacheControl(CacheControl.NoCache(null))
                        call.respondBytesWriter(contentType = ContentType.Text.EventStream) {
                            streamWriter = this@respondBytesWriter
                            withTimeout(timeoutMs) {
                                inferenceMutex.withLock {
                                    RequestTracker.markStarted(entry.id)
                                    acquireWakeLock(timeoutMs) {
                                        runInferenceStreaming(
                                            conversation = resolvedLocal.conversation,
                                            prompt = resolvedLocal.prompt,
                                            writer = this@respondBytesWriter,
                                            responseId = responseId,
                                            requestEntryId = entry.id,
                                            modelName = req.model,
                                            gson = gson,
                                            serviceScope = serviceScope,
                                            onChunk = { chunk -> RequestTracker.recordChunk(entry.id, chunk) },
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        val finalMsg = withTimeout(timeoutMs) {
                            inferenceMutex.withLock {
                                RequestTracker.markStarted(entry.id)
                                var result: LlmMessage? = null
                                acquireWakeLock(timeoutMs) {
                                    result = withContext(Dispatchers.Default) {
                                        resolvedLocal.conversation.sendMessage(resolvedLocal.prompt, emptyMap())
                                    }
                                }
                                result!!
                            }
                        }
                        val responseText = LlmMessageConverter.messageText(finalMsg)
                        RequestTracker.recordChunk(entry.id, responseText)

                        val toolCalls = finalMsg.toolCalls
                        val choice = if (!toolCalls.isNullOrEmpty()) {
                            Choice(
                                index = 0,
                                message = Message(
                                    role = "assistant",
                                    content = null,
                                    toolCalls = toolCalls.mapIndexed { i, tc ->
                                        ToolCallApi(
                                            id = "call_${entry.id}_$i",
                                            type = "function",
                                            function = ToolCallFunction(
                                                name = tc.name,
                                                arguments = gson.toJson(tc.arguments),
                                            ),
                                        )
                                    },
                                ),
                                finishReason = "tool_calls",
                            )
                        } else {
                            Choice(
                                index = 0,
                                message = Message(role = "assistant", content = stringContent(responseText)),
                                finishReason = "stop",
                            )
                        }
                        call.respond(ChatResponse(
                            id = responseId,
                            `object` = "chat.completion",
                            created = System.currentTimeMillis() / 1000,
                            model = req.model,
                            choices = listOf(choice),
                        ))
                    }
                    inferenceOk = true
                    RequestTracker.markCompleted(entry.id)
                    lastActivityAt.set(System.currentTimeMillis())
                }
            }
        } catch (te: TimeoutCancellationException) {
            LogManager.e("ChatRoute", "Request #${entry.id} timed out after ${timeoutMs} ms")
            try { resolved?.conversation?.cancelProcess() } catch (_: Exception) {}
            RequestTracker.markCompleted(entry.id, error = "timeout after ${timeoutMs} ms")
            val w = streamWriter
            if (w != null) {
                writeSseError(w, "Inference timeout", "timeout", 408, gson)
            } else {
                try {
                    call.respond(
                        HttpStatusCode.RequestTimeout,
                        ErrorResponse(ErrorDetails("Inference timeout", "timeout", 408)),
                    )
                } catch (_: Exception) { /* stream already started */ }
            }
        } catch (ce: kotlinx.coroutines.CancellationException) {
            try { resolved?.conversation?.cancelProcess() } catch (_: Exception) {}
            RequestTracker.markCompleted(entry.id, cancelled = true)
            throw ce
        } catch (e: Exception) {
            LogManager.e("ChatRoute", "Request #${entry.id} error", e)
            RequestTracker.markCompleted(entry.id, error = e.message ?: e.javaClass.simpleName)
            val w = streamWriter
            // AICore failures get a structured envelope so the client can
            // distinguish background-blocked (ErrorCode 30) from other runtime
            // problems. LiteRT failures and unknowns collapse to the generic
            // server_error path.
            if (req.model == AICoreEngine.MODEL_ID) {
                val (httpStatus, details) = aicoreRuntimeEnvelope(e)
                if (w != null) writeSseRichError(w, details, gson)
                else try { call.respond(httpStatus, RichErrorResponse(details)) } catch (_: Exception) {}
            } else if (w != null) {
                writeSseError(w, e.message ?: "Unknown error", "server_error", 500, gson)
            } else {
                try {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        ErrorResponse(ErrorDetails(
                            message = e.message ?: "Unknown error",
                            type = "server_error",
                            code = 500,
                        )),
                    )
                } catch (_: Exception) { /* stream already started */ }
            }
        } finally {
            val r = resolved
            if (r != null) {
                if (r.isCached) {
                    if (inferenceOk) sessionManager.commit(r, req.messages)
                    else sessionManager.invalidate(r)
                } else {
                    sessionManager.closeIfStateless(r)
                }
            }
        }
    }
}

/**
 * Flatten an OpenAI chat-history into a single string prompt for AICore.
 * AICore's `GenerateContentRequest` takes a `TextPart(string)` only; there
 * is no first-class system / role slot.
 */
private fun flattenForAICore(messages: List<Message>): String {
    val sb = StringBuilder()
    for (m in messages) {
        val text = m.contentString() ?: m.contentParts()
            .filterIsInstance<ContentPart.TextPart>()
            .joinToString(" ") { it.text }
        if (text.isBlank()) continue
        val label = when (m.role) {
            "system" -> "system"
            "assistant" -> "assistant"
            "tool" -> "tool"
            else -> "user"
        }
        sb.append(label).append(": ").append(text).append("\n\n")
    }
    sb.append("assistant: ")
    return sb.toString()
}

private suspend fun runAICoreStreaming(
    writer: ByteWriteChannel,
    prompt: String,
    temperature: Float?,
    topK: Int?,
    maxOutputTokens: Int?,
    responseId: String,
    modelName: String,
    requestEntryId: String,
    gson: Gson,
    onChunk: (String) -> Unit,
) {
    suspend fun safeWrite(s: String) {
        try { writer.writeStringUtf8(s); writer.flush() } catch (_: Throwable) { /* peer gone */ }
    }
    val initResp = StreamResponse(
        id = responseId,
        `object` = "chat.completion.chunk",
        created = System.currentTimeMillis() / 1000,
        model = modelName,
        choices = listOf(StreamChoice(0, StreamDelta(role = "assistant"), null)),
    )
    safeWrite("data: ${gson.toJson(initResp)}\n\n")

    var prev = ""
    AICoreEngine.stream(
        prompt = prompt,
        temperature = temperature,
        topK = topK,
        maxOutputTokens = maxOutputTokens,
    ).collect { full ->
        val delta = when {
            full.startsWith(prev) && full.length > prev.length -> full.substring(prev.length)
            full == prev -> ""
            else -> full
        }
        if (delta.isEmpty()) return@collect
        prev = if (full.startsWith(prev)) full else prev + delta
        onChunk(delta)
        val chunkResp = StreamResponse(
            id = responseId,
            `object` = "chat.completion.chunk",
            created = System.currentTimeMillis() / 1000,
            model = modelName,
            choices = listOf(StreamChoice(0, StreamDelta(content = delta), null)),
        )
        safeWrite("data: ${gson.toJson(chunkResp)}\n\n")
    }
    val finalResp = StreamResponse(
        id = responseId,
        `object` = "chat.completion.chunk",
        created = System.currentTimeMillis() / 1000,
        model = modelName,
        choices = listOf(StreamChoice(0, StreamDelta(), "stop")),
    )
    safeWrite("data: ${gson.toJson(finalResp)}\n\n")
    safeWrite("data: [DONE]\n\n")
}

private suspend fun runInferenceStreaming(
    conversation: com.google.ai.edge.litertlm.Conversation,
    prompt: LlmMessage,
    writer: ByteWriteChannel,
    responseId: String,
    requestEntryId: String,
    modelName: String,
    gson: Gson,
    serviceScope: CoroutineScope,
    onChunk: (String) -> Unit = {},
) {
    val writeMutex = Mutex()
    suspend fun safeWrite(s: String) {
        writeMutex.withLock {
            writer.writeStringUtf8(s)
            writer.flush()
        }
    }
    val heartbeat = serviceScope.launch {
        while (isActive) {
            delay(10_000L)
            try { safeWrite(": ka\n\n") } catch (_: Throwable) { return@launch }
        }
    }
    try {
        val initResp = StreamResponse(
            id = responseId,
            `object` = "chat.completion.chunk",
            created = System.currentTimeMillis() / 1000,
            model = modelName,
            choices = listOf(StreamChoice(0, StreamDelta(role = "assistant"), null)),
        )
        safeWrite("data: ${gson.toJson(initResp)}\n\n")

        var prev = ""
        var lastToolCalls: List<ToolCall>? = null
        conversation.sendMessageAsync(prompt, emptyMap()).collect { msg ->
            msg.toolCalls?.takeIf { it.isNotEmpty() }?.let { lastToolCalls = it }

            val full = LlmMessageConverter.messageText(msg)
            val delta = if (full.startsWith(prev) && full.length > prev.length) full.substring(prev.length)
                        else if (full == prev) ""
                        else full
            if (delta.isNotEmpty()) {
                prev = if (full.startsWith(prev)) full else prev + delta
                onChunk(delta)
                val chunkResp = StreamResponse(
                    id = responseId,
                    `object` = "chat.completion.chunk",
                    created = System.currentTimeMillis() / 1000,
                    model = modelName,
                    choices = listOf(StreamChoice(0, StreamDelta(content = delta), null)),
                )
                safeWrite("data: ${gson.toJson(chunkResp)}\n\n")
            }
        }

        val tc = lastToolCalls
        val finalResp = if (!tc.isNullOrEmpty()) {
            StreamResponse(
                id = responseId,
                `object` = "chat.completion.chunk",
                created = System.currentTimeMillis() / 1000,
                model = modelName,
                choices = listOf(
                    StreamChoice(
                        0,
                        StreamDelta(
                            toolCalls = tc.mapIndexed { i, t ->
                                ToolCallApi(
                                    id = "call_${requestEntryId}_$i",
                                    type = "function",
                                    function = ToolCallFunction(
                                        name = t.name,
                                        arguments = gson.toJson(t.arguments),
                                    ),
                                )
                            },
                        ),
                        "tool_calls",
                    ),
                ),
            )
        } else {
            StreamResponse(
                id = responseId,
                `object` = "chat.completion.chunk",
                created = System.currentTimeMillis() / 1000,
                model = modelName,
                choices = listOf(StreamChoice(0, StreamDelta(), "stop")),
            )
        }
        safeWrite("data: ${gson.toJson(finalResp)}\n\n")
        safeWrite("data: [DONE]\n\n")
    } finally {
        heartbeat.cancel()
    }
}

private suspend fun writeSseError(
    writer: ByteWriteChannel,
    message: String,
    type: String,
    code: Int,
    gson: Gson,
) {
    try {
        val json = gson.toJson(ErrorResponse(ErrorDetails(message, type, code)))
        writer.writeStringUtf8("data: $json\n\n")
        writer.writeStringUtf8("data: [DONE]\n\n")
        writer.flush()
    } catch (_: java.io.IOException) {
        /* Client gone; nothing actionable. */
    } catch (_: Exception) {
        /* Defensive: never let error-reporting itself throw out of a catch arm. */
    }
}

/**
 * SSE-side counterpart to the JSON [RichErrorResponse]. Used when an AICore
 * failure fires AFTER the SSE response has already committed headers —
 * serialises the envelope into the open stream and follows with the
 * `[DONE]` sentinel so clients close cleanly.
 */
private suspend fun writeSseRichError(
    writer: ByteWriteChannel,
    details: RichErrorDetails,
    gson: Gson,
) {
    try {
        val json = gson.toJson(RichErrorResponse(details))
        writer.writeStringUtf8("data: $json\n\n")
        writer.writeStringUtf8("data: [DONE]\n\n")
        writer.flush()
    } catch (_: java.io.IOException) {
    } catch (_: Exception) {
    }
}

/**
 * Build the AICore "not ready" structured envelope keyed by the SDK status
 * code from [AICoreEngine.checkStatusCode]. Returns the envelope and the
 * HTTP status to use:
 *   - DOWNLOADABLE → 503 (Service Unavailable), actionable=true
 *   - DOWNLOADING  → 425 (Too Early), actionable=true
 *   - UNAVAILABLE  → 503, actionable=false
 *   - any other    → 503, actionable=false
 *
 * [probeError] is non-null when the SDK threw during checkStatus — the
 * underlying message is folded into the envelope so the client sees the
 * real reason (typically ErrorCode -101 / "AICore not installed").
 */
internal fun aicoreNotReadyEnvelope(
    statusCode: Int,
    probeError: Throwable? = null,
): Pair<HttpStatusCode, RichErrorDetails> {
    val label = AICoreEngine.statusLabel(statusCode)
    return when (statusCode) {
        AICoreEngine.STATUS_DOWNLOADABLE -> HttpStatusCode.ServiceUnavailable to RichErrorDetails(
            message = "AICore (Gemini Nano) is downloadable on this device. Tap the Models tab to provision it before retrying.",
            type = "aicore_unavailable",
            code = "AICORE_DOWNLOADABLE",
            aicoreStatus = label,
            actionable = true,
            nextSteps = listOf(
                "Open Models tab",
                "Tap 'Download Gemini Nano'",
                "Wait for status: Available",
            ),
        )
        AICoreEngine.STATUS_DOWNLOADING -> HttpStatusCode(425, "Too Early") to RichErrorDetails(
            message = "AICore (Gemini Nano) is downloading. Retry once the Models tab reports 'Available'.",
            type = "aicore_unavailable",
            code = "AICORE_DOWNLOADING",
            aicoreStatus = label,
            actionable = true,
            nextSteps = listOf(
                "Wait for the Models tab to show: Available",
                "Retry the request",
            ),
        )
        AICoreEngine.STATUS_UNAVAILABLE -> {
            val reason = probeError?.message?.let { ": $it" } ?: ""
            HttpStatusCode.ServiceUnavailable to RichErrorDetails(
                message = "AICore (Gemini Nano) is not available on this device$reason. Requires Pixel 8+ with the AICore Developer Preview or an OEM build that ships the AICore system service.",
                type = "aicore_unavailable",
                code = "AICORE_UNAVAILABLE",
                aicoreStatus = label,
                actionable = false,
                nextSteps = listOf(
                    "Switch to a different model (e.g. gemma-4-e2b)",
                ),
            )
        }
        else -> HttpStatusCode.ServiceUnavailable to RichErrorDetails(
            message = "AICore (Gemini Nano) is $label on this device.",
            type = "aicore_unavailable",
            code = "AICORE_UNKNOWN",
            aicoreStatus = label,
            actionable = false,
            nextSteps = emptyList(),
        )
    }
}

/**
 * Build the AICore runtime-failure envelope. Distinguishes ErrorCode 30
 * (host activity not foreground) so the client can surface a foreground
 * warning; other generate-side failures collapse to a generic runtime error.
 *   - background-blocked → 403 (Forbidden), actionable=true
 *   - other runtime      → 500, actionable=false
 */
internal fun aicoreRuntimeEnvelope(t: Throwable): Pair<HttpStatusCode, RichErrorDetails> {
    val msg = t.message.orEmpty()
    val isBackgroundBlocked = msg.contains("ErrorCode 30") ||
        msg.contains("error code 30", ignoreCase = true) ||
        msg.contains("Background usage is blocked", ignoreCase = true) ||
        msg.contains("activity is not in foreground", ignoreCase = true) ||
        (msg.contains("foreground", ignoreCase = true) && msg.contains("aicore", ignoreCase = true))
    return if (isBackgroundBlocked) {
        HttpStatusCode.Forbidden to RichErrorDetails(
            message = "AICore (Gemini Nano) refused the call because the host app isn't in the foreground. Open the LocalLLM app and keep it visible while the HTTP client queries this model.",
            type = "aicore_background_blocked",
            code = "AICORE_BACKGROUND_BLOCKED",
            aicoreStatus = "available",
            actionable = true,
            nextSteps = listOf(
                "Bring the LocalLLM app to the foreground",
                "Keep the Chat tab visible while the external client is connected",
                "Retry the request",
            ),
        )
    } else {
        HttpStatusCode.InternalServerError to RichErrorDetails(
            message = "AICore (Gemini Nano) call failed: ${t.message ?: t.javaClass.simpleName}",
            type = "aicore_runtime_error",
            code = "AICORE_RUNTIME_ERROR",
            aicoreStatus = "unknown",
            actionable = false,
            nextSteps = emptyList(),
        )
    }
}

/**
 * Build the LiteRT engine-init envelope. The acquire step throws when the
 * model file is missing, the SoC marker doesn't match, or the vendor NPU
 * delegate isn't loadable — surface a structured response with concrete
 * next steps instead of a bare 500.
 */
internal fun liteRtAcquireEnvelope(modelId: String, t: Throwable): Pair<HttpStatusCode, RichErrorDetails> {
    return HttpStatusCode.ServiceUnavailable to RichErrorDetails(
        message = "LiteRT-LM engine failed to initialise for '$modelId': ${t.message ?: t.javaClass.simpleName}. The model file may be missing, the vendor delegate may be unavailable, or the .litertlm bundle may be incompatible with this device's SoC.",
        type = "litert_engine_failed",
        code = "LITERT_INIT_FAILED",
        actionable = true,
        nextSteps = listOf(
            "Open the Models tab and confirm the model is downloaded",
            "If the model is NPU-gated, check the SoC label matches this device",
            "Try the AICore (gemini-nano-aicore) model instead",
        ),
    )
}
