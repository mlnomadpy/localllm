package com.localllm.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentCallbacks2
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.serialization.gson.*
import io.ktor.server.request.*
import io.ktor.http.HttpStatusCode
import io.ktor.http.ContentType
import io.ktor.http.CacheControl
import com.localllm.app.rag.resolveTenantFromHeaders
import io.ktor.utils.io.*
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ToolCall
import com.google.ai.edge.litertlm.ToolProvider
import com.google.ai.edge.litertlm.tool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.util.Base64
import android.util.LruCache
import okhttp3.OkHttpClient
import okhttp3.Request as OkRequest
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import com.google.ai.edge.litertlm.Message as LlmMessage

/**
 * Process-wide server state. The UI observes this to render the status badge,
 * the Start/Stop button, and the bound URL.
 */
object ServerState {
    enum class Status { STOPPED, STARTING, RUNNING, ERROR }

    private val _status = MutableStateFlow(Status.STOPPED)
    val status = _status.asStateFlow()

    private val _boundUrl = MutableStateFlow<String?>(null)
    val boundUrl = _boundUrl.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError = _lastError.asStateFlow()

    internal fun setStatus(s: Status) { _status.value = s }
    internal fun setBoundUrl(url: String?) { _boundUrl.value = url }
    internal fun setError(msg: String?) { _lastError.value = msg }
}

class LLMServerService : Service() {
    private var server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>? = null

    /**
     * Engine cache. Keyed by `model_maxTokens_backend` because LiteRT-LM's
     * `EngineConfig.maxNumTokens` is the *total* KV-cache budget
     * (input + output), not just an output cap. Reusing an engine built
     * with a larger budget for a request that asked for less would let the
     * model overgenerate. Each model takes 1–2 GB so we keep at most 2 resident.
     *
     * When an engine is evicted, every conversation that holds a handle to it is
     * also evicted first — conversations can't outlive their parent engine.
     */
    /**
     * Wraps the engine alongside the resolved backend label and the
     * full chain of attempts that produced it. Surfacing the attempts
     * in `/health` keeps the user honest about what actually happened
     * — silent fallback hides too much, especially on Tensor SoCs
     * where some backends only work after a JNI primer step.
     */
    private data class CachedEngine(
        val engine: Engine,
        val backend: String,
        val attempts: List<BackendAttempt>,
    )

    /**
     * One step in the engine-init chain. Surfaced via `/health` so the
     * user can see exactly which backends were tried, what failed and
     * why, and which one ultimately produced the cached engine.
     */
    private data class BackendAttempt(
        val backend: String,
        val result: String,
        val durationMs: Long,
    )

    private val engines = object : LruCache<String, CachedEngine>(2) {
        override fun entryRemoved(evicted: Boolean, key: String?, oldValue: CachedEngine?, newValue: CachedEngine?) {
            if (evicted) {
                // Close conversations tied to this engine BEFORE closing the engine —
                // a conversation referencing a closed engine is undefined behavior.
                if (key != null) {
                    val tied = sessions.snapshot().filter { it.value.engineKey == key }.keys
                    tied.forEach { sessions.remove(it) }
                    // Drop the per-engine "active conversation" tracking entry.
                    activeConversations.remove(key)?.let { conv ->
                        try { conv.close() } catch (_: Exception) {}
                    }
                }
                try {
                    oldValue?.engine?.close()
                    LogManager.i("LLMServerService", "Evicted engine: $key")
                } catch (e: Exception) {
                    LogManager.e("LLMServerService", "Error closing evicted engine", e)
                }
            }
        }
    }

    /**
     * Tracks the single live [Conversation] per engine cache key. LiteRT-LM's
     * Engine constraint is "at most one conversation per engine at a time"; we
     * use this map to find and close the prior conversation before constructing
     * a new one. Distinct from [sessions] (which only tracks *cached* sessioned
     * conversations) because stateless conversations also need to be tracked.
     */
    private val activeConversations = ConcurrentHashMap<String, Conversation>()

    /**
     * Cached `Conversation`s keyed by `session_id + engineKey`. Conversations
     * preserve the KV cache across turns: on a follow-up request we only need
     * to send the NEW user turns. Saves the cost of re-tokenizing and
     * re-prefilling the full conversation each time.
     *
     * Bounded at 4 cached conversations — beyond that the LRU evicts oldest.
     */
    private data class CachedSession(
        val conversation: Conversation,
        val engineKey: String,
        val temperature: Float,
        val topK: Int,
        val prefixHash: Long,
        val seenCount: Int,
        val createdAt: Long
    )

    private val sessions = object : LruCache<String, CachedSession>(4) {
        override fun entryRemoved(evicted: Boolean, key: String?, oldValue: CachedSession?, newValue: CachedSession?) {
            // Always close the conversation — both eviction and explicit removal go
            // through here. Compare by identity so a put() that replaces the
            // entry with the SAME conversation doesn't accidentally close it.
            if (oldValue != null && oldValue.conversation !== newValue?.conversation) {
                try { oldValue.conversation.close() } catch (_: Exception) {}
            }
        }
    }

    /**
     * What [resolveSession] returns. Carries enough state for the caller to
     * either commit (on success) or invalidate (on failure) the conversation.
     */
    private data class ResolvedSession(
        val conversation: Conversation,
        /** The freshly-arrived message to send via sendMessage[Async]. Already
         *  shaped for LiteRT-LM (user / tool follow-up content). */
        val prompt: LlmMessage,
        val cacheKey: String?,        // null for stateless (no session_id)
        val engineKey: String,
        val temperature: Float,
        val topK: Int
    ) {
        val isCached: Boolean get() = cacheKey != null
    }

    private val inferenceMutex = Mutex()
    private val gson = Gson()

    /**
     * Token-bucket rate limiter keyed on the request's `User-Agent` header.
     * Lazily reconfigured on each request from the Settings flow — cheap
     * (volatile var write) and means changing the per-second rate in the
     * UI takes effect on the next call without restarting the service.
     */
    private val rateLimiter = RateLimiter(
        ratePerSec = Settings.DEFAULT_RATE_LIMIT_PER_SEC,
        burst = Settings.DEFAULT_RATE_LIMIT_BURST,
    )

    /**
     * Lazy ObjectBox-backed document store. Opens on first /v1/documents or
     * /v1/search request; closed in onDestroy.
     */
    @Volatile private var documentStoreRef: com.localllm.app.rag.DocumentStore? = null
    private fun documentStore(): com.localllm.app.rag.DocumentStore {
        documentStoreRef?.let { return it }
        synchronized(this) {
            documentStoreRef?.let { return it }
            val s = com.localllm.app.rag.DocumentStore(this)
            documentStoreRef = s
            return s
        }
    }

    /**
     * Embedding service cache. Keyed by model id (the basename without
     * extension). At most one resident — the model is small (~127 MB for
     * bge-small) but each ORT session still holds non-trivial RAM, and we
     * never need two concurrent embedding models.
     */
    private val embeddings = object : LruCache<String, com.localllm.app.embedding.EmbeddingService>(1) {
        override fun entryRemoved(
            evicted: Boolean,
            key: String?,
            oldValue: com.localllm.app.embedding.EmbeddingService?,
            newValue: com.localllm.app.embedding.EmbeddingService?,
        ) {
            if (oldValue != null && oldValue !== newValue) {
                try { oldValue.close() } catch (_: Exception) {}
                if (evicted) LogManager.i("LLMServerService", "Evicted embedding model: $key")
            }
        }
    }

    /**
     * Last time we received a request. Used for idle-based engine eviction and
     * optional service auto-stop.
     */
    private val lastActivityAt = AtomicLong(System.currentTimeMillis())

    /** Service-scoped coroutines (idle monitor, etc.). Cancelled on onDestroy. */
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Default + serviceJob)
    private var idleMonitorJob: Job? = null

    /** Lazily-acquired partial wake lock — only held while inference is active. */
    private val wakeLock by lazy {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LocalLLM:Inference").apply {
            setReferenceCounted(false)
        }
    }

    private fun getModelFile(modelId: String): File {
        val filename = if (modelId.endsWith(".litertlm")) modelId else "$modelId.litertlm"
        return File(getExternalFilesDir(null), filename)
    }

    /**
     * Look up the vocab file paired with an ONNX embedding model. The
     * convention is `<modelId>-vocab.txt` next to `<modelId>.onnx` in the
     * app's external files dir. Returns null if absent — the model is then
     * considered unavailable (we can't tokenize without it).
     */
    private fun resolveVocabFor(modelId: String): File? {
        val dir = getExternalFilesDir(null) ?: return null
        val candidates = listOf(
            File(dir, "$modelId-vocab.txt"),
            File(dir, "$modelId.vocab.txt"),
            File(dir, "${modelId}_vocab.txt"),
        )
        return candidates.firstOrNull { it.exists() }
    }

    private fun getOrCreateEmbeddingService(modelId: String): com.localllm.app.embedding.EmbeddingService {
        embeddings.get(modelId)?.let { return it }
        val dir = getExternalFilesDir(null) ?: error("external files dir unavailable")
        val modelFile = File(dir, "$modelId.onnx")
        if (!modelFile.exists()) error("model file ${modelFile.name} not found")
        val vocabFile = resolveVocabFor(modelId)
            ?: error("vocab file for '$modelId' not found (expected $modelId-vocab.txt)")
        val svc = com.localllm.app.embedding.EmbeddingService(
            modelPath = modelFile.absolutePath,
            vocabPath = vocabFile.absolutePath,
        )
        embeddings.put(modelId, svc)
        return svc
    }

    override fun onCreate() {
        super.onCreate()
        startForeground()
        startServer()
        startIdleMonitor()
    }

    /**
     * Background loop that:
     *   - evicts cached engines after [Settings.idleEvictMs] of no activity
     *     (frees ~1–2 GB per model)
     *   - optionally stops the service entirely after [Settings.idleStopMs]
     *
     * Both are configurable; 0 disables.
     */
    private fun startIdleMonitor() {
        idleMonitorJob?.cancel()
        idleMonitorJob = serviceScope.launch {
            while (isActive) {
                delay(30_000L)
                val idleMs = System.currentTimeMillis() - lastActivityAt.get()
                val evictAfter = Settings.idleEvictMs(this@LLMServerService)
                val stopAfter = Settings.idleStopMs(this@LLMServerService)

                if (evictAfter > 0 && idleMs >= evictAfter && engines.size() > 0) {
                    // Only evict if nothing is currently running.
                    if (inferenceMutex.tryLock()) {
                        try {
                            val n = engines.size()
                            engines.evictAll()
                            if (n > 0) LogManager.i("LLMServerService", "Idle eviction: released $n engine(s) after ${idleMs / 1000}s idle")
                        } finally {
                            inferenceMutex.unlock()
                        }
                    }
                }

                // Embedding services are independent of the LM inferenceMutex,
                // but they're cheap to recreate (cold ~700 ms on Pixel 6) so
                // eviction is safe and frees the ORT session memory.
                if (evictAfter > 0 && idleMs >= evictAfter && embeddings.size() > 0) {
                    val n = embeddings.size()
                    embeddings.evictAll()
                    LogManager.i("LLMServerService", "Idle eviction: released $n embedding model(s) after ${idleMs / 1000}s idle")
                }

                if (stopAfter > 0 && idleMs >= stopAfter) {
                    LogManager.i("LLMServerService", "Idle auto-stop after ${idleMs / 1000}s")
                    stopSelf()
                    return@launch
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        // START_STICKY contract: when the OS kills us under memory pressure
        // (LMK) Android will re-create the service later with a `null` intent.
        // That's fine — [onCreate] unconditionally calls [startServer] and
        // [startIdleMonitor], so the HTTP listener is back on its bound port
        // within ~2s of the cold-start. The original triggering intent is
        // intentionally not redelivered (we don't need REDELIVER_INTENT — the
        // service has no per-intent work, only ambient long-running state).
        return START_STICKY
    }

    /**
     * Memory-pressure callback. Each cached engine pins 2–3 GB of model weights,
     * so dropping even one entry under pressure is often the difference between
     * surviving the next LMK pass and being killed cold.
     *
     * Contract: this fires on the main thread, so we only do the bookkeeping
     * inline (mutex try-lock + log) and offload the actual `evictAll`/`remove`
     * work to [serviceScope] so the system callback returns immediately.
     * Eviction never interrupts an active inference — if [inferenceMutex] is
     * held we just bail and log.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        when (level) {
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE,
            ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> {
                LogManager.i("MemoryPressure", "trim level=$level, action=log-only (moderate/background)")
            }
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
            ComponentCallbacks2.TRIM_MEMORY_MODERATE -> {
                serviceScope.launch {
                    if (inferenceMutex.tryLock()) {
                        try {
                            val before = engines.size()
                            if (before > 1) {
                                engines.trimToSize(1)
                                LogManager.i("MemoryPressure", "trim level=$level, action=shrunk LRU from $before to ${engines.size()}")
                            } else {
                                LogManager.i("MemoryPressure", "trim level=$level, action=noop (engines=$before)")
                            }
                        } finally {
                            inferenceMutex.unlock()
                        }
                    } else {
                        LogManager.i("MemoryPressure", "trim level=$level, action=skipped (inference active)")
                    }
                }
            }
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
            ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> {
                serviceScope.launch {
                    if (inferenceMutex.tryLock()) {
                        try {
                            val nEngines = engines.size()
                            val nSessions = sessions.size()
                            val nEmb = embeddings.size()
                            // Sessions hold conversations tied to engines; clear them
                            // first so the engine eviction callback doesn't double-close.
                            sessions.evictAll()
                            engines.evictAll()
                            embeddings.evictAll()
                            LogManager.i("MemoryPressure", "trim level=$level, action=evicted all ($nEngines engines, $nSessions sessions, $nEmb embedding models)")
                        } finally {
                            inferenceMutex.unlock()
                        }
                    } else {
                        LogManager.w("MemoryPressure", "trim level=$level, action=could-not-acquire-lock (inference active; LMK may kill us)")
                    }
                }
            }
            ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> {
                // App went to background. The idle eviction loop already handles
                // long-running background; evicting eagerly here would just thrash
                // (evict + reload) every time the user tabs out.
                LogManager.i("MemoryPressure", "trim level=$level, action=noop (UI hidden; idle loop handles background)")
            }
            else -> {
                LogManager.i("MemoryPressure", "trim level=$level, action=noop (unknown level)")
            }
        }
    }

    private fun startForeground() {
        val port = Settings.port(this)

        val channelId = "llm_service_channel"
        val chan = NotificationChannel(channelId, getString(R.string.notif_channel_name), NotificationManager.IMPORTANCE_LOW)
        val manager = (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
        manager.createNotificationChannel(chan)

        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, LLMServerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val host = Settings.bindHost(this)
        val notification = NotificationCompat.Builder(this, channelId)
            .setOngoing(true)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_listening, host, port))
            .setContentIntent(openIntent)
            .addAction(0, getString(R.string.notif_action_stop), stopIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

        startForeground(1, notification)
    }

    private fun startServer() {
        ServerState.setStatus(ServerState.Status.STARTING)
        ServerState.setError(null)
        try {
            val port = Settings.port(this)
            val host = Settings.bindHost(this)

            server = embeddedServer(Netty, port = port, host = host) {
                install(ContentNegotiation) { gson() }
                // CORS is opt-in. Native HTTP clients don't need CORS headers,
                // so the safe default is "no CORS plugin installed" — that way
                // a random web page can't drive the API from a user's browser.
                if (Settings.allowCors(this@LLMServerService)) {
                    install(CORS) {
                        anyHost()
                        allowMethod(io.ktor.http.HttpMethod.Post)
                        allowMethod(io.ktor.http.HttpMethod.Get)
                        allowHeader(io.ktor.http.HttpHeaders.ContentType)
                        allowHeader(io.ktor.http.HttpHeaders.Authorization)
                    }
                }

                routing {
                    get("/health") {
                        call.respond(mapOf(
                            "status" to "ok",
                            "service" to "localllm-android",
                            "version" to "1.0",
                            "queue_depth" to RequestTracker.queue.value.size,
                            "engines_loaded" to engines.size(),
                            "engines" to engines.snapshot().map { (key, v) ->
                                mapOf(
                                    "key" to key,
                                    "backend" to v.backend,
                                    "attempts" to v.attempts.map {
                                        mapOf(
                                            "backend" to it.backend,
                                            "result" to it.result,
                                            "duration_ms" to it.durationMs,
                                        )
                                    },
                                )
                            }
                        ))
                    }

                    get("/v1/models") {
                        if (!authorize(call)) return@get
                        val dir = getExternalFilesDir(null)
                        val all = dir?.listFiles() ?: emptyArray()
                        val llmModels = all.filter { it.name.endsWith(".litertlm") }.map { file ->
                            val modelId = file.name.removeSuffix(".litertlm")
                            ModelData(id = modelId, created = file.lastModified() / 1000)
                        }
                        // Surface ONNX embedding models too. We only count a model
                        // as available if its sibling vocab file is present —
                        // the tokenizer can't be reconstructed from the .onnx alone.
                        val embModels = all.filter { it.name.endsWith(".onnx") }
                            .mapNotNull { file ->
                                val modelId = file.name.removeSuffix(".onnx")
                                if (resolveVocabFor(modelId) == null) null
                                else ModelData(id = modelId, created = file.lastModified() / 1000)
                            }
                        // Virtual entry for the AICore (Gemini Nano) backend.
                        // Listed unconditionally so clients can probe; the
                        // chat handler surfaces a clean error if AICore isn't
                        // installed or the model isn't downloaded yet.
                        val aicoreModel = ModelData(
                            id = com.localllm.app.aicore.AICoreEngine.MODEL_ID,
                            created = System.currentTimeMillis() / 1000,
                            ownedBy = "google-aicore",
                        )
                        call.respond(ModelListResponse(data = llmModels + embModels + aicoreModel))
                    }

                    post("/v1/embeddings") {
                        if (!authorize(call)) return@post
                        lastActivityAt.set(System.currentTimeMillis())

                        val req = try {
                            call.receive<EmbeddingRequest>()
                        } catch (e: Exception) {
                            call.respond(
                                HttpStatusCode.BadRequest,
                                ErrorResponse(ErrorDetails(
                                    message = "Invalid request body: ${e.message ?: e.javaClass.simpleName}",
                                    type = "invalid_request_error",
                                    code = 400
                                ))
                            )
                            return@post
                        }

                        if (req.encodingFormat != null && req.encodingFormat != "float") {
                            call.respond(
                                HttpStatusCode.BadRequest,
                                ErrorResponse(ErrorDetails(
                                    message = "encoding_format='${req.encodingFormat}' is not supported (only 'float')",
                                    type = "invalid_request_error",
                                    code = 400
                                ))
                            )
                            return@post
                        }

                        val texts = try { req.inputStrings() } catch (e: IllegalArgumentException) {
                            call.respond(
                                HttpStatusCode.BadRequest,
                                ErrorResponse(ErrorDetails(
                                    message = e.message ?: "invalid input",
                                    type = "invalid_request_error",
                                    code = 400
                                ))
                            )
                            return@post
                        }

                        // Same prompt-cap budget as chat: protect against
                        // someone POSTing a massive document.
                        val maxChars = Settings.maxPromptChars(this@LLMServerService)
                        val totalChars = texts.sumOf { it.length }
                        if (totalChars > maxChars) {
                            call.respond(
                                HttpStatusCode.PayloadTooLarge,
                                ErrorResponse(ErrorDetails(
                                    message = "Total input length $totalChars exceeds cap of $maxChars chars",
                                    type = "invalid_request_error",
                                    code = 413
                                ))
                            )
                            return@post
                        }

                        val svc = try {
                            getOrCreateEmbeddingService(req.model)
                        } catch (e: Exception) {
                            call.respond(
                                HttpStatusCode.NotFound,
                                ErrorResponse(ErrorDetails(
                                    message = "Embedding model '${req.model}' not available: ${e.message ?: e.javaClass.simpleName}",
                                    type = "invalid_request_error",
                                    code = 404
                                ))
                            )
                            return@post
                        }

                        try {
                            val results = svc.embed(texts)
                            val data = results.mapIndexed { i, (vec, _) ->
                                EmbeddingData(embedding = vec, index = i)
                            }
                            val tokens = results.sumOf { it.second }
                            call.respond(EmbeddingResponse(
                                data = data,
                                model = req.model,
                                usage = EmbeddingUsage(promptTokens = tokens, totalTokens = tokens),
                            ))
                        } catch (e: Throwable) {
                            LogManager.e("LLMServerService", "Embedding inference failed: ${e.message}", e)
                            call.respond(
                                HttpStatusCode.InternalServerError,
                                ErrorResponse(ErrorDetails(
                                    message = "Embedding inference failed: ${e.message ?: e.javaClass.simpleName}",
                                    type = "api_error",
                                    code = 500
                                ))
                            )
                        }
                    }

                    /* ----- /v1/documents (RAG corpus management) ----- */

                    post("/v1/documents") {
                        if (!authorize(call)) return@post
                        lastActivityAt.set(System.currentTimeMillis())
                        val tenantId = tenantFromCall(call)

                        val req = try {
                            call.receive<DocumentRequest>()
                        } catch (e: Exception) {
                            call.respond(
                                HttpStatusCode.BadRequest,
                                ErrorResponse(ErrorDetails(
                                    "Invalid request body: ${e.message ?: e.javaClass.simpleName}",
                                    "invalid_request_error", 400
                                ))
                            )
                            return@post
                        }
                        if (req.id.isBlank() || req.text.isBlank() || req.model.isBlank()) {
                            call.respond(
                                HttpStatusCode.BadRequest,
                                ErrorResponse(ErrorDetails(
                                    "id, text, and model are required",
                                    "invalid_request_error", 400
                                ))
                            )
                            return@post
                        }

                        val maxChars = Settings.maxPromptChars(this@LLMServerService)
                        if (req.text.length > maxChars * 50) {
                            // 50x the prompt cap is the rough budget for a
                            // single uploaded document — beyond that the
                            // caller should split client-side.
                            call.respond(
                                HttpStatusCode.PayloadTooLarge,
                                ErrorResponse(ErrorDetails(
                                    "Document text exceeds the per-upload size cap",
                                    "invalid_request_error", 413
                                ))
                            )
                            return@post
                        }

                        val chunks = com.localllm.app.rag.Chunker.chunk(req.text)
                        if (chunks.isEmpty()) {
                            call.respond(
                                HttpStatusCode.BadRequest,
                                ErrorResponse(ErrorDetails(
                                    "Document text is empty after trimming",
                                    "invalid_request_error", 400
                                ))
                            )
                            return@post
                        }

                        val svc = try {
                            getOrCreateEmbeddingService(req.model)
                        } catch (e: Exception) {
                            call.respond(
                                HttpStatusCode.NotFound,
                                ErrorResponse(ErrorDetails(
                                    "Embedding model '${req.model}' not available: ${e.message ?: e.javaClass.simpleName}",
                                    "invalid_request_error", 404
                                ))
                            )
                            return@post
                        }

                        try {
                            val vectors = svc.embed(chunks)
                            val metadataStr = req.metadata?.toString()
                            val entities = chunks.mapIndexed { i, text ->
                                com.localllm.app.rag.DocumentChunk(
                                    tenantId = tenantId,
                                    documentId = req.id,
                                    chunkIndex = i,
                                    text = text,
                                    metadata = metadataStr,
                                    embeddingModel = req.model,
                                    embedding = vectors[i].first,
                                )
                            }
                            // Replace prior chunks for this id so re-POSTing
                            // the same id is an upsert, not a duplicate.
                            documentStore().deleteDocument(tenantId, req.id)
                            documentStore().put(entities)
                            call.respondWithTenant(
                                tenantId,
                                DocumentSummaryResponse(
                                    documentId = req.id,
                                    chunkCount = entities.size,
                                    model = req.model,
                                    tenantId = tenantId,
                                ),
                            )
                        } catch (e: Throwable) {
                            LogManager.e("LLMServerService", "Document ingest failed: ${e.message}", e)
                            call.respond(
                                HttpStatusCode.InternalServerError,
                                ErrorResponse(ErrorDetails(
                                    "Document ingest failed: ${e.message ?: e.javaClass.simpleName}",
                                    "api_error", 500
                                ))
                            )
                        }
                    }

                    get("/v1/documents") {
                        if (!authorize(call)) return@get
                        val tenantId = tenantFromCall(call)
                        val summaries = documentStore().listDocuments(tenantId).map {
                            DocumentSummaryResponse(
                                documentId = it.documentId,
                                chunkCount = it.chunkCount,
                                model = it.embeddingModel,
                                tenantId = tenantId,
                            )
                        }
                        call.respondWithTenant(
                            tenantId,
                            DocumentListResponse(data = summaries, tenantId = tenantId),
                        )
                    }

                    delete("/v1/documents/{id}") {
                        if (!authorize(call)) return@delete
                        val tenantId = tenantFromCall(call)
                        val id = call.parameters["id"]
                        if (id.isNullOrBlank()) {
                            call.respond(
                                HttpStatusCode.BadRequest,
                                ErrorResponse(ErrorDetails("id is required", "invalid_request_error", 400))
                            )
                            return@delete
                        }
                        val n = documentStore().deleteDocument(tenantId, id)
                        call.respondWithTenant(
                            tenantId,
                            DocumentDeleteResponse(
                                documentId = id,
                                deleted = n > 0,
                                chunksRemoved = n,
                                tenantId = tenantId,
                            ),
                        )
                    }

                    /* ----- /v1/tenants (admin — global view, not tenant-scoped) ----- */

                    get("/v1/tenants") {
                        if (!authorize(call)) return@get
                        val summaries = documentStore().listTenants().map {
                            TenantSummaryResponse(
                                tenantId = it.tenantId,
                                documentCount = it.documentCount,
                                chunkCount = it.chunkCount,
                            )
                        }
                        call.respond(TenantListResponse(data = summaries))
                    }

                    delete("/v1/tenants/{tenantId}") {
                        if (!authorize(call)) return@delete
                        val tenantId = call.parameters["tenantId"]?.trim()?.lowercase().orEmpty()
                        if (tenantId.isBlank()) {
                            call.respond(
                                HttpStatusCode.BadRequest,
                                ErrorResponse(ErrorDetails("tenantId is required", "invalid_request_error", 400))
                            )
                            return@delete
                        }
                        val n = documentStore().deleteTenant(tenantId)
                        call.respond(TenantDeleteResponse(
                            tenantId = tenantId,
                            deleted = n > 0,
                            chunksRemoved = n,
                        ))
                    }

                    /* ----- /v1/search (kNN over the document store) ----- */

                    post("/v1/search") {
                        if (!authorize(call)) return@post
                        lastActivityAt.set(System.currentTimeMillis())
                        val tenantId = tenantFromCall(call)

                        val req = try { call.receive<SearchRequest>() } catch (e: Exception) {
                            call.respond(
                                HttpStatusCode.BadRequest,
                                ErrorResponse(ErrorDetails(
                                    "Invalid request body: ${e.message ?: e.javaClass.simpleName}",
                                    "invalid_request_error", 400
                                ))
                            )
                            return@post
                        }
                        if (req.query.isBlank() || req.model.isBlank()) {
                            call.respond(
                                HttpStatusCode.BadRequest,
                                ErrorResponse(ErrorDetails("query and model are required", "invalid_request_error", 400))
                            )
                            return@post
                        }
                        val k = (req.k ?: 5).coerceIn(1, 50)

                        val svc = try {
                            getOrCreateEmbeddingService(req.model)
                        } catch (e: Exception) {
                            call.respond(
                                HttpStatusCode.NotFound,
                                ErrorResponse(ErrorDetails(
                                    "Embedding model '${req.model}' not available: ${e.message ?: e.javaClass.simpleName}",
                                    "invalid_request_error", 404
                                ))
                            )
                            return@post
                        }

                        try {
                            val queryVec = svc.embed(listOf(req.query)).first().first
                            val hits = documentStore().nearest(tenantId, queryVec, k, req.model).map { (chunk, distance) ->
                                // ObjectBox DOT_PRODUCT distance is `1 - cosine` for
                                // unit-norm vectors. Surface cosine so clients see
                                // numbers in the familiar [-1, 1] range.
                                val cosine = 1f - distance
                                val metaJson: com.google.gson.JsonElement? = chunk.metadata?.let {
                                    runCatching { com.google.gson.JsonParser.parseString(it) }.getOrNull()
                                }
                                SearchHit(
                                    documentId = chunk.documentId,
                                    chunkIndex = chunk.chunkIndex,
                                    text = chunk.text,
                                    score = cosine,
                                    metadata = metaJson,
                                )
                            }
                            call.respondWithTenant(
                                tenantId,
                                SearchResponse(data = hits, model = req.model, tenantId = tenantId),
                            )
                        } catch (e: Throwable) {
                            LogManager.e("LLMServerService", "Search failed: ${e.message}", e)
                            call.respond(
                                HttpStatusCode.InternalServerError,
                                ErrorResponse(ErrorDetails(
                                    "Search failed: ${e.message ?: e.javaClass.simpleName}",
                                    "api_error", 500
                                ))
                            )
                        }
                    }

                    post("/v1/chat/completions") {
                        if (!authorize(call)) return@post
                        lastActivityAt.set(System.currentTimeMillis())

                        // Per-client rate limit. Identity is the User-Agent
                        // header — sibling apps each send their own UA by
                        // default, so this gives them isolated buckets without
                        // any explicit per-app key management. When the limit
                        // is disabled (rate=0 in Settings) tryAcquire is a no-op.
                        val clientId = call.request.headers["User-Agent"]?.takeIf { it.isNotBlank() } ?: "anonymous"
                        val rate = Settings.rateLimitPerSec(this@LLMServerService)
                        if (rate > 0.0) {
                            rateLimiter.ratePerSec = rate
                            rateLimiter.burst = Settings.rateLimitBurst(this@LLMServerService)
                            val retryAfter = rateLimiter.tryAcquire(clientId)
                            if (retryAfter != null) {
                                call.response.headers.append("Retry-After", retryAfter.toString())
                                call.response.headers.append("X-RateLimit-Client", clientId)
                                call.respond(
                                    HttpStatusCode.TooManyRequests,
                                    ErrorResponse(ErrorDetails(
                                        message = "Rate limit for client '$clientId' exhausted; retry in ${retryAfter}s.",
                                        type = "rate_limit_error",
                                        code = 429
                                    ))
                                )
                                return@post
                            }
                        }

                        // Pre-parse body-size guard. The prompt-char cap below
                        // fires AFTER JSON parsing, which is too late if the
                        // body itself is huge. ~2 bytes per char covers JSON
                        // escapes and structure overhead with margin.
                        val maxChars = Settings.maxPromptChars(this@LLMServerService)
                        val bodyCap = maxChars.toLong() * 2L + 8_192L
                        val contentLength = call.request.headers["Content-Length"]?.toLongOrNull()
                        if (contentLength != null && contentLength > bodyCap) {
                            call.respond(
                                HttpStatusCode.PayloadTooLarge,
                                ErrorResponse(ErrorDetails(
                                    message = "Request body of $contentLength bytes exceeds cap of $bodyCap",
                                    type = "invalid_request_error",
                                    code = 413
                                ))
                            )
                            return@post
                        }

                        val req = try {
                            call.receive<ChatRequest>()
                        } catch (e: Exception) {
                            // Surface the underlying parser exception so polymorphic-content
                            // shape errors don't disappear behind Ktor's generic wrapper.
                            LogManager.e("LLMServerService", "Failed to parse ChatRequest body", e)
                            val rootCause = generateSequence(e as Throwable?) { it.cause }.lastOrNull() ?: e
                            call.respond(
                                HttpStatusCode.BadRequest,
                                ErrorResponse(ErrorDetails(
                                    message = "Invalid JSON body: ${rootCause.javaClass.simpleName}: ${rootCause.message ?: e.message}",
                                    type = "invalid_request_error",
                                    code = 400
                                ))
                            )
                            return@post
                        }
                        val promptChars = req.messages.sumOf { it.textChars() }

                        // Prompt-size cap (413)
                        if (promptChars > maxChars) {
                            call.respond(
                                HttpStatusCode.PayloadTooLarge,
                                ErrorResponse(ErrorDetails(
                                    message = "Prompt of $promptChars chars exceeds limit of $maxChars",
                                    type = "invalid_request_error",
                                    code = 413
                                ))
                            )
                            return@post
                        }

                        // Queue cap (429) — atomic vs other concurrent requests
                        val maxDepth = Settings.maxQueueDepth(this@LLMServerService)
                        val entry = RequestTracker.tryEnqueue(
                            model = req.model,
                            stream = req.stream,
                            messageCount = req.messages.size,
                            promptChars = promptChars,
                            maxDepth = maxDepth,
                            client = clientId,
                        )
                        if (entry == null) {
                            call.response.headers.append("Retry-After", "5")
                            call.respond(
                                HttpStatusCode.TooManyRequests,
                                ErrorResponse(ErrorDetails(
                                    message = "Queue full ($maxDepth in flight). Retry shortly.",
                                    type = "rate_limit_error",
                                    code = 429
                                ))
                            )
                            return@post
                        }

                        // Queue-position feedback. Position is 1-based and
                        // counts ahead-of-us (entries that need to grab the
                        // inference mutex before this one). Estimated wait is
                        // queue_position × recent avg inference time, clamped
                        // to be positive. Both headers are sent before the
                        // response body so SSE clients can render a progress
                        // hint immediately, without waiting for the first
                        // token.
                        val queueDepth = RequestTracker.queue.value.size
                        val queuePosition = RequestTracker.queue.value.indexOfFirst { it.id == entry.id } + 1
                        val avgInfMs = RequestTracker.stats.value.avgLatencyMs
                        val estimatedWaitMs = (queuePosition - 1).coerceAtLeast(0) * avgInfMs
                        call.response.headers.append("X-Queue-Position", queuePosition.toString())
                        call.response.headers.append("X-Queue-Depth", queueDepth.toString())
                        call.response.headers.append("X-Estimated-Wait-Ms", estimatedWaitMs.toString())
                        call.response.headers.append("X-Request-Id", entry.id)
                        call.response.headers.append("X-Client-Id", clientId)

                        // Client origin — useful when multiple apps share the server
                        val remoteIp = call.request.local.remoteHost
                        val ua = clientId
                        val timeoutMs = Settings.requestTimeoutMs(this@LLMServerService)

                        // Session lifecycle: resolved once outside the inference, committed
                        // (or invalidated) once inference resolves either way.
                        var resolved: ResolvedSession? = null
                        var inferenceOk = false
                        var streamWriter: io.ktor.utils.io.ByteWriteChannel? = null
                        try {
                            LogManager.i("LLMServerService", "Request #${entry.id} from $remoteIp [$ua]: model=${req.model}, stream=${req.stream}, msgs=${req.messages.size}, chars=$promptChars, session=${req.sessionId?.ifEmpty { null } ?: "(stateless)"}")

                            val responseId = "chatcmpl-${entry.id}"
                            val temp = req.temperature ?: Settings.temperature(this@LLMServerService)
                            val topK = req.topK ?: Settings.topK(this@LLMServerService)

                            // AICore (Gemini Nano) bypass. No LiteRT-LM engine,
                            // no .litertlm on disk, no inferenceMutex — AICore
                            // runs in the system service and handles its own
                            // serialization. Sessions / KV reuse don't apply;
                            // every call is stateless from our side.
                            if (req.model == com.localllm.app.aicore.AICoreEngine.MODEL_ID) {
                                val status = com.localllm.app.aicore.AICoreEngine.checkStatusCode()
                                if (status != com.localllm.app.aicore.AICoreEngine.STATUS_AVAILABLE) {
                                    throw IllegalStateException(
                                        "AICore (Gemini Nano) is ${com.localllm.app.aicore.AICoreEngine.statusLabel(status)} on this device. " +
                                        "Requires Pixel 8+ with the AICore system service; on a fresh device the model may need to download via AICore before the first call succeeds."
                                    )
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
                                                onChunk = { delta -> RequestTracker.recordChunk(entry.id, delta) },
                                            )
                                        }
                                    }
                                } else {
                                    val text = withTimeout(timeoutMs) {
                                        RequestTracker.markStarted(entry.id)
                                        com.localllm.app.aicore.AICoreEngine.complete(
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

                            val handle = getOrCreateEngine(req)
                            val resolvedLocal = resolveSession(req, handle, temp, topK)
                            resolved = resolvedLocal
                            val needWakeLock = Settings.keepAwake(this@LLMServerService)

                            if (req.stream) {
                                call.response.cacheControl(CacheControl.NoCache(null))
                                call.respondBytesWriter(contentType = ContentType.Text.EventStream) {
                                    streamWriter = this@respondBytesWriter
                                    withTimeout(timeoutMs) {
                                        inferenceMutex.withLock {
                                            RequestTracker.markStarted(entry.id)
                                            withWakeLock(needWakeLock, timeoutMs) {
                                                runInferenceStreaming(
                                                    conversation = resolvedLocal.conversation,
                                                    prompt = resolvedLocal.prompt,
                                                    writer = this@respondBytesWriter,
                                                    responseId = responseId,
                                                    requestEntryId = entry.id,
                                                    modelName = req.model,
                                                    onChunk = { chunk -> RequestTracker.recordChunk(entry.id, chunk) }
                                                )
                                            }
                                        }
                                    }
                                }
                            } else {
                                val finalMsg = withTimeout(timeoutMs) {
                                    inferenceMutex.withLock {
                                        RequestTracker.markStarted(entry.id)
                                        withWakeLock(needWakeLock, timeoutMs) {
                                            withContext(Dispatchers.Default) {
                                                runInferenceBlocking(resolvedLocal.conversation, resolvedLocal.prompt)
                                            }
                                        }
                                    }
                                }
                                val responseText = messageText(finalMsg)
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
                                                        arguments = gson.toJson(tc.arguments)
                                                    )
                                                )
                                            }
                                        ),
                                        finishReason = "tool_calls"
                                    )
                                } else {
                                    Choice(
                                        index = 0,
                                        message = Message(role = "assistant", content = stringContent(responseText)),
                                        finishReason = "stop"
                                    )
                                }

                                val resp = ChatResponse(
                                    id = responseId,
                                    `object` = "chat.completion",
                                    created = System.currentTimeMillis() / 1000,
                                    model = req.model,
                                    choices = listOf(choice)
                                )
                                call.respond(resp)
                            }
                            inferenceOk = true
                            RequestTracker.markCompleted(entry.id)
                            lastActivityAt.set(System.currentTimeMillis())
                        } catch (te: TimeoutCancellationException) {
                            LogManager.e("LLMServerService", "Request #${entry.id} timed out after ${timeoutMs} ms")
                            // Tell the native engine to stop, otherwise generation
                            // keeps burning compute after the HTTP request is dead.
                            try { resolved?.conversation?.cancelProcess() } catch (_: Exception) {}
                            RequestTracker.markCompleted(entry.id, error = "timeout after ${timeoutMs} ms")
                            val w = streamWriter
                            if (w != null) {
                                writeSseError(w, "Inference timeout", "timeout", 408)
                            } else {
                                try {
                                    call.respond(
                                        HttpStatusCode.RequestTimeout,
                                        ErrorResponse(ErrorDetails("Inference timeout", "timeout", 408))
                                    )
                                } catch (_: Exception) { /* stream already started */ }
                            }
                        } catch (ce: kotlinx.coroutines.CancellationException) {
                            try { resolved?.conversation?.cancelProcess() } catch (_: Exception) {}
                            RequestTracker.markCompleted(entry.id, cancelled = true)
                            throw ce
                        } catch (e: Exception) {
                            LogManager.e("LLMServerService", "Request #${entry.id} error", e)
                            RequestTracker.markCompleted(entry.id, error = e.message ?: e.javaClass.simpleName)
                            val w = streamWriter
                            if (w != null) {
                                writeSseError(w, e.message ?: "Unknown error", "server_error", 500)
                            } else {
                                try {
                                    call.respond(
                                        HttpStatusCode.InternalServerError,
                                        ErrorResponse(ErrorDetails(
                                            message = e.message ?: "Unknown error",
                                            type = "server_error",
                                            code = 500
                                        ))
                                    )
                                } catch (_: Exception) { /* stream already started */ }
                            }
                        } finally {
                            val r = resolved
                            if (r != null) {
                                if (r.isCached) {
                                    if (inferenceOk) commitSession(r, req.messages)
                                    else invalidateSession(r)
                                } else {
                                    // Stateless: close the one-shot conversation regardless of outcome.
                                    closeIfStateless(r)
                                }
                            }
                        }
                    }
                }
            }.start(wait = false)

            val displayHost = if (host == "0.0.0.0") getLanIp() ?: "0.0.0.0" else host
            ServerState.setBoundUrl("http://$displayHost:$port")
            ServerState.setStatus(ServerState.Status.RUNNING)
            LogManager.i("LLMServerService", "Server listening on http://$displayHost:$port")
        } catch (e: Exception) {
            ServerState.setStatus(ServerState.Status.ERROR)
            val msg = e.message ?: e.javaClass.simpleName
            ServerState.setError("Failed to start server: $msg")
            ServerState.setBoundUrl(null)
            LogManager.e("LLMServerService", "Failed to start server", e)
        }
    }

    private fun getLanIp(): String? {
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (addr is java.net.Inet4Address && !addr.isLoopbackAddress) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (e: Exception) {
            LogManager.e("LLMServerService", "Failed to get LAN IP", e)
        }
        return null
    }

    /**
     * Pulls the plain-text content out of a LiteRT-LM [LlmMessage]. Multimodal
     * outputs (images / audio) are ignored — we only render text chunks back
     * to the OpenAI-compatible client.
     */
    private fun messageText(msg: LlmMessage): String {
        val parts = msg.contents.contents
        if (parts.isEmpty()) return ""
        val sb = StringBuilder()
        for (p in parts) {
            if (p is Content.Text) sb.append(p.text)
        }
        return sb.toString()
    }

    /**
     * Streaming inference using a pre-built [conversation]. The conversation's
     * lifecycle is owned by the caller — this function never closes it.
     *
     * Writes OpenAI-style SSE chunks to [writer], notifies [onChunk] for stats,
     * and emits a heartbeat comment every 10s so long TTFTs aren't killed by
     * intermediaries or idle-connection detectors.
     *
     * Each [LlmMessage] emitted by LiteRT-LM is treated as a cumulative snapshot
     * of the generation so far; we diff against the previous snapshot to extract
     * the delta. If we instead receive deltas (some LiteRT-LM build configs do
     * that), the diff logic still produces the right result because the previous
     * snapshot never becomes a prefix of an unrelated string.
     *
     * Caller is expected to hold [inferenceMutex].
     */
    private suspend fun runInferenceStreaming(
        conversation: Conversation,
        prompt: LlmMessage,
        writer: ByteWriteChannel,
        responseId: String,
        requestEntryId: String,
        modelName: String,
        onChunk: (String) -> Unit = {}
    ) {
        val writeMutex = Mutex()  // serialize writer access across heartbeat + chunks

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
                choices = listOf(StreamChoice(0, StreamDelta(role = "assistant"), null))
            )
            safeWrite("data: ${gson.toJson(initResp)}\n\n")

            var prev = ""
            var lastToolCalls: List<ToolCall>? = null
            conversation.sendMessageAsync(prompt, emptyMap()).collect { msg ->
                // Track the most recent toolCalls snapshot — when a model decides
                // to invoke a tool it shows up on the terminal message of the flow.
                msg.toolCalls?.takeIf { it.isNotEmpty() }?.let { lastToolCalls = it }

                val full = messageText(msg)
                val delta = if (full.startsWith(prev) && full.length > prev.length) full.substring(prev.length)
                            else if (full == prev) ""
                            else full   // not a prefix → treat as delta-mode emission
                if (delta.isNotEmpty()) {
                    prev = if (full.startsWith(prev)) full else prev + delta
                    onChunk(delta)
                    val chunkResp = StreamResponse(
                        id = responseId,
                        `object` = "chat.completion.chunk",
                        created = System.currentTimeMillis() / 1000,
                        model = modelName,
                        choices = listOf(StreamChoice(0, StreamDelta(content = delta), null))
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
                                            arguments = gson.toJson(t.arguments)
                                        )
                                    )
                                }
                            ),
                            "tool_calls"
                        )
                    )
                )
            } else {
                StreamResponse(
                    id = responseId,
                    `object` = "chat.completion.chunk",
                    created = System.currentTimeMillis() / 1000,
                    model = modelName,
                    choices = listOf(StreamChoice(0, StreamDelta(), "stop"))
                )
            }
            safeWrite("data: ${gson.toJson(finalResp)}\n\n")
            safeWrite("data: [DONE]\n\n")
        } finally {
            heartbeat.cancel()
        }
    }

    /**
     * Non-streaming inference. Like [runInferenceStreaming], the conversation is
     * caller-owned. Caller is expected to hold [inferenceMutex].
     */
    private fun runInferenceBlocking(conversation: Conversation, prompt: LlmMessage): LlmMessage {
        return conversation.sendMessage(prompt, emptyMap())
    }

    /**
     * Flatten an OpenAI chat-history into a single string prompt for AICore.
     *
     * AICore's `GenerateContentRequest` takes a `TextPart(string)`; there is
     * no first-class system / role slot the way OpenAI exposes. So we render
     * the conversation as labelled turns and append a bare `assistant:`
     * suffix to cue the model toward the next reply. Multimodal parts are
     * collapsed to their text fragments; image inputs aren't forwarded (the
     * SDK supports them via `ImagePart`, but wiring multimodal through this
     * one-string contract isn't worth it until a caller asks).
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

    /**
     * Stream AICore output back to [writer] in the OpenAI SSE shape used by
     * the rest of `/v1/chat/completions`. AICore emits cumulative text on
     * each `GenerateContentResponse`, so we compute the delta vs the prior
     * chunk before forwarding — same trick `runInferenceStreaming` uses for
     * LiteRT-LM's prefix-style emissions.
     */
    private suspend fun runAICoreStreaming(
        writer: io.ktor.utils.io.ByteWriteChannel,
        prompt: String,
        temperature: Float?,
        topK: Int?,
        maxOutputTokens: Int?,
        responseId: String,
        modelName: String,
        requestEntryId: String,
        onChunk: (String) -> Unit,
    ) {
        suspend fun safeWrite(s: String) {
            try { writer.writeStringUtf8(s); writer.flush() } catch (_: Throwable) { /* peer gone */ }
        }
        // OpenAI streams emit a role-only delta first, then content deltas.
        val initResp = StreamResponse(
            id = responseId,
            `object` = "chat.completion.chunk",
            created = System.currentTimeMillis() / 1000,
            model = modelName,
            choices = listOf(StreamChoice(0, StreamDelta(role = "assistant"), null)),
        )
        safeWrite("data: ${gson.toJson(initResp)}\n\n")

        var prev = ""
        com.localllm.app.aicore.AICoreEngine.stream(
            prompt = prompt,
            temperature = temperature,
            topK = topK,
            maxOutputTokens = maxOutputTokens,
        ).collect { full ->
            val delta = when {
                full.startsWith(prev) && full.length > prev.length -> full.substring(prev.length)
                full == prev -> ""
                else -> full // not a prefix → emit verbatim
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

    /**
     * Emit an OpenAI-shaped error as a final SSE chunk followed by the [DONE]
     * sentinel. Used when an exception fires AFTER the SSE response has already
     * committed headers — at that point [call.respond] is a no-op, so the only
     * way to tell the client what went wrong is to write into the open stream.
     *
     * Swallows IOException because the client may have already disconnected.
     */
    private suspend fun writeSseError(writer: io.ktor.utils.io.ByteWriteChannel, message: String, type: String, code: Int) {
        try {
            val json = gson.toJson(ErrorResponse(ErrorDetails(message, type, code)))
            writer.writeStringUtf8("data: $json\n\n")
            writer.writeStringUtf8("data: [DONE]\n\n")
            writer.flush()
        } catch (_: java.io.IOException) {
            // Client gone; nothing actionable.
        } catch (_: Exception) {
            // Defensive: never let error-reporting itself throw out of a catch arm.
        }
    }

    /**
     * Returns true if the request carries a valid bearer token (or auth is disabled).
     * On failure, writes a 401 response and returns false — the caller should bail out.
     */
    private suspend fun authorize(call: ApplicationCall): Boolean {
        val configured = Settings.apiKey(this)
        if (configured.isEmpty()) return true
        val header = call.request.headers["Authorization"]
        val ok = header != null && header.startsWith("Bearer ") &&
            header.substring(7).trim() == configured
        if (!ok) {
            call.response.headers.append("WWW-Authenticate", "Bearer")
            call.respond(
                HttpStatusCode.Unauthorized,
                ErrorResponse(ErrorDetails("Invalid or missing API key", "invalid_api_key", 401))
            )
        }
        return ok
    }

    /** Resolve the RAG tenant for [call] — see `resolveTenantFromHeaders`. */
    private fun tenantFromCall(call: ApplicationCall): String =
        resolveTenantFromHeaders(
            clientId = call.request.headers["X-Client-Id"],
            userAgent = call.request.headers["User-Agent"],
        )

    /** Attach `X-Tenant-Id` and emit a JSON body that includes `tenant_id`. */
    private suspend inline fun <reified T : Any> ApplicationCall.respondWithTenant(
        tenantId: String,
        body: T,
    ) {
        response.headers.append("X-Tenant-Id", tenantId)
        respond(body)
    }

    /**
     * Hold a partial wake lock for the duration of [block]. The lock has a hard
     * timeout slightly larger than the inference budget so a buggy code path
     * can't drain the battery forever.
     */
    private suspend inline fun <T> withWakeLock(enabled: Boolean, timeoutMs: Long, crossinline block: suspend () -> T): T {
        if (!enabled) return block()
        @Suppress("WakelockTimeout")
        wakeLock.acquire(timeoutMs + 5_000L)
        return try {
            block()
        } finally {
            try { if (wakeLock.isHeld) wakeLock.release() } catch (_: Exception) {}
        }
    }

    /**
     * Build the LiteRT-LM [Content] list backing one API [Message]. Handles all
     * three on-wire shapes for `content`:
     *   - `null` (only valid for assistant messages w/ tool_calls — yields empty)
     *   - JsonPrimitive string  → `[Content.Text(...)]`
     *   - JsonArray of `{type:"text"|"image_url",...}` → mixed text/image
     *
     * Image URLs are decoded inline: `data:` are base64-decoded immediately;
     * `http://localhost*` are fetched via OkHttp on the caller's thread, capped
     * at 5 MB. Anything else throws [IllegalArgumentException] which the route
     * handler converts to a 400.
     *
     * Images larger than 1024×1024 are downscaled with `inSampleSize` and
     * re-encoded as JPEG@85% so prefill stays reasonable.
     */
    private fun buildContents(message: Message): List<Content> {
        val parts = message.contentParts()
        if (parts.isEmpty()) return emptyList()
        val out = mutableListOf<Content>()
        for (p in parts) {
            when (p) {
                is ContentPart.TextPart -> if (p.text.isNotEmpty()) out += Content.Text(p.text)
                is ContentPart.ImagePart -> out += Content.ImageBytes(loadImageBytes(p.url))
            }
        }
        return out
    }

    /** Lazy shared client for fetching `http://localhost*` image URLs. */
    private val imageHttp: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    private val maxImageBytes = 5L * 1024L * 1024L
    private val maxImageDim = 1024

    /**
     * Decode an OpenAI `image_url.url` into JPEG bytes ready for LiteRT-LM.
     * Strict allowlist: `data:` URLs and `http://localhost(:port)/...` only.
     * Anything else is a 400 (SSRF protection — the model server shouldn't
     * make outbound requests to arbitrary networks).
     */
    private fun loadImageBytes(url: String): ByteArray {
        val raw: ByteArray = when {
            url.startsWith("data:") -> decodeDataImageUrl(url)
            isLoopbackHttpUrl(url) -> {
                val resp = imageHttp.newCall(OkRequest.Builder().url(url).build()).execute()
                resp.use { r ->
                    if (!r.isSuccessful) {
                        throw IllegalArgumentException("Failed to fetch image_url: HTTP ${r.code}")
                    }
                    val cl = r.body?.contentLength() ?: -1L
                    if (cl in 1..Long.MAX_VALUE && cl > maxImageBytes) {
                        throw IllegalArgumentException("Image too large: $cl bytes (max $maxImageBytes)")
                    }
                    val src = r.body?.byteStream() ?: throw IllegalArgumentException("Empty body")
                    val buf = ByteArrayOutputStream()
                    val tmp = ByteArray(16 * 1024)
                    var total = 0L
                    while (true) {
                        val n = src.read(tmp)
                        if (n <= 0) break
                        total += n
                        if (total > maxImageBytes) {
                            throw IllegalArgumentException("Image exceeds $maxImageBytes-byte cap")
                        }
                        buf.write(tmp, 0, n)
                    }
                    buf.toByteArray()
                }
            }
            else -> throw IllegalArgumentException(
                "image_url scheme not allowed; only data: and http://localhost are supported"
            )
        }
        return downscaleIfNeeded(raw)
    }

    /** Decode + downscale + re-encode JPEG if dimensions exceed [maxImageDim]. */
    private fun downscaleIfNeeded(bytes: ByteArray): ByteArray {
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOpts)
        val w = boundsOpts.outWidth
        val h = boundsOpts.outHeight
        if (w <= 0 || h <= 0) {
            throw IllegalArgumentException("Could not decode image (invalid format or corrupt bytes)")
        }
        if (w <= maxImageDim && h <= maxImageDim) return bytes

        var sample = 1
        while (w / sample > maxImageDim || h / sample > maxImageDim) sample *= 2

        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts)
            ?: throw IllegalArgumentException("Image decode returned null")
        val out = ByteArrayOutputStream()
        try {
            bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
        } finally {
            bmp.recycle()
        }
        return out.toByteArray()
    }

    /**
     * Translate one API [Message] into a LiteRT-LM [LlmMessage]. Tool-related
     * roles get special treatment:
     *   - `role: "assistant"` with `tool_calls`: emitted as a model turn whose
     *     `toolCalls` list mirrors what the model previously asked the client
     *     to do (so the engine can stitch the conversation back together).
     *   - `role: "tool"` with `content`: emitted as a tool turn with a
     *     [Content.ToolResponse] carrying the serialized result string.
     *
     * Multi-part content (text + image) is preserved; plain string content
     * collapses to a single [Content.Text].
     */
    private fun apiToLlmMessage(m: Message): LlmMessage {
        return when (m.role) {
            "assistant" -> {
                val toolCalls = m.toolCalls?.map { tc ->
                    ToolCall(tc.function.name, parseToolArguments(tc.function.arguments))
                } ?: emptyList()
                val contents = buildContents(m)
                LlmMessage.Companion.model(
                    Contents.of(contents),
                    toolCalls,
                    emptyMap()
                )
            }
            "system" -> LlmMessage.Companion.system(
                Contents.of(buildContents(m))
            )
            "tool" -> {
                // OpenAI tool messages put the serialized result in `content`
                // (typically a JSON-encoded string). Pass it straight through —
                // the model will see whatever the client returned.
                val payload = m.contentString() ?: (m.content?.toString() ?: "")
                // We don't know which named tool produced the response in the
                // OpenAI shape (it carries only `tool_call_id`). Use the
                // tool_call_id as a best-effort name; the runtime treats it as
                // a label.
                val name = m.toolCallId ?: "tool"
                LlmMessage.Companion.tool(
                    Contents.of(Content.ToolResponse(name, payload))
                )
            }
            else -> LlmMessage.Companion.user(Contents.of(buildContents(m)))
        }
    }

    /**
     * Build a LiteRT-LM [ToolProvider] from an OpenAI-shaped function definition.
     * We construct an [OpenApiTool] whose `toolDescriptionJsonString` is the
     * OpenAI parameters schema wrapped under the canonical
     * `{name, description, parameters}` envelope LiteRT-LM expects.
     *
     * `automaticToolCalling` is disabled on the conversation, so [execute] is
     * never actually called by the runtime — the model emits a `toolCall` and
     * the server forwards it to the HTTP client. We still implement [execute]
     * defensively so a future runtime version that auto-calls won't crash;
     * it returns a "not implemented" JSON envelope.
     */
    private fun buildToolProvider(def: ToolDef): ToolProvider {
        val name = def.function.name
        val descJson = buildToolDescriptionJson(name, def.function.description, def.function.parameters)
        val openApi = object : OpenApiTool {
            override fun getToolDescriptionJsonString(): String = descJson
            override fun execute(paramsJsonString: String): String {
                // Server-side execution is intentionally not implemented — the
                // HTTP client owns tool execution. If the runtime ever calls
                // this, return a structured error rather than throwing.
                return "{\"error\":\"tool_execution_not_implemented\",\"tool\":\"$name\"}"
            }
        }
        return tool(openApi)
    }

    /**
     * Build a fresh [Conversation] pre-loaded with the OpenAI-style chat history
     * minus the final user turn (which the caller will send via sendMessage).
     * System messages are collapsed into [ConversationConfig.systemInstruction];
     * the rest become [ConversationConfig.initialMessages] so they prefill the
     * KV cache without triggering generation.
     */
    private fun createConversation(
        engine: Engine,
        engineKey: String,
        temperature: Float,
        topK: Int,
        systemText: String?,
        initial: List<Message>,
        tools: List<ToolDef>?,
    ): Conversation {
        // LiteRT-LM enforces *one active Conversation per Engine*. If a prior
        // request's conversation didn't fully release (silent close() failure,
        // mid-stream cancellation, or just timing of native cleanup) the next
        // engine.createConversation() throws FAILED_PRECONDITION
        // "A session already exists". Close any conversation we know about on
        // this engine before creating a new one — covers both the cached
        // sessions LRU and the per-engine "currently active" slot we track.
        purgeConversationsOnEngine(engineKey)

        val systemInstruction = systemText?.takeIf { it.isNotBlank() }?.let { Contents.of(it) }
        val priorMessages = initial.map { m -> apiToLlmMessage(m) }
        val toolProviders: List<ToolProvider> = tools?.map { def -> buildToolProvider(def) } ?: emptyList()
        // automaticToolCalling MUST be false. LiteRT-LM 0.11.0 defaults this to
        // true via the 4-arg ConversationConfig overload, which causes the
        // runtime to invoke our OpenApiTool.execute() stub and feed the result
        // straight back to the model — bypassing the HTTP client entirely. The
        // OpenAI contract is "model emits tool_calls, client executes, client
        // sends a role:tool follow-up." Passing false here is what enables that
        // round-trip; the tool call surfaces in Message.toolCalls instead of
        // being silently consumed by the engine.
        val cfg = ConversationConfig(
            systemInstruction,
            priorMessages,
            toolProviders,
            SamplerConfig(topK, /*topP=*/0.95, temperature.toDouble(), /*seed=*/0),
            /*automaticToolCalling=*/ false
        )
        val conv = try {
            engine.createConversation(cfg)
        } catch (e: Exception) {
            // Defensive: if LiteRT-LM still reports a stale session despite our
            // pre-purge, force-evict the engine (Engine.close() releases all
            // native conversation slots) and surface a clear error. Caller may
            // retry with a fresh getOrCreateEngine pass.
            if (e.message?.contains("session already exists", ignoreCase = true) == true) {
                LogManager.w("LLMServerService", "Engine $engineKey stuck with leaked conversation; evicting.")
                engines.remove(engineKey)
                throw IllegalStateException("Engine had a stuck conversation; evicted. Please retry the request.", e)
            }
            throw e
        }
        activeConversations[engineKey] = conv
        return conv
    }

    /**
     * Close every conversation we know about on the given engine — both the
     * cached sessions in [sessions] and the per-engine "currently active" slot
     * in [activeConversations]. Idempotent and silently swallows close()
     * exceptions (the native side may have already cleaned up).
     */
    private fun purgeConversationsOnEngine(engineKey: String) {
        // Evict cached sessions tied to this engine — entryRemoved closes them.
        val staleKeys = sessions.snapshot().filter { it.value.engineKey == engineKey }.keys
        staleKeys.forEach { sessions.remove(it) }
        // Close whatever we last marked as active on this engine.
        activeConversations.remove(engineKey)?.let { prior ->
            try { prior.close() } catch (_: Exception) {}
        }
    }

    private data class EngineHandle(val engine: Engine, val cacheKey: String)

    /**
     * Map an explicit user backend choice (CPU / GPU) to a [Backend] instance.
     * NOT used for AUTO — that path is resolved in [getOrCreateEngine] with a
     * try/fallback so it can actually probe what works on this device.
     */
    private fun resolveBackend(choice: String): Backend = when (choice) {
        Settings.BACKEND_GPU -> Backend.GPU()
        else                 -> Backend.CPU()
    }

    /**
     * Construct + initialize a fresh engine on the given backend. Throws on
     * any failure (lib-missing, OOM, op unsupported, model corrupt, …). Kept
     * as a small seam so the AUTO fallback path can call this twice without
     * duplicating the EngineConfig wiring.
     */
    /**
     * Returns true when running on a Google Tensor SoC (Pixel 6 and later).
     * LiteRT-LM 0.11.0 has known stability issues on this family that
     * require backend-specific workarounds — see [primeTensorJniState] and
     * the BACKEND_GPU branch of [getOrCreateEngine].
     *
     * `Build.SOC_MODEL` is API 31+. On older devices we return false; the
     * Tensor family launched on Android 12, so this is correct by
     * construction.
     */
    private fun isTensorSoc(): Boolean {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) return false
        val soc = android.os.Build.SOC_MODEL?.lowercase() ?: return false
        // Pixels report the SoC codename via ro.soc.model, not the marketing
        // name. Tensor G1..G4 → GS101/GS201/ZUMA/ZUMA_PRO; G5 → LAGUNA.
        // Verified LAGUNA on a Pixel 10 device.
        if (soc.contains("tensor")) return true
        return soc.startsWith("gs10") ||
            soc.startsWith("gs20") ||
            soc == "zuma" ||
            soc == "zuma_pro" ||
            soc == "laguna"
    }

    /**
     * Time one backend attempt and append the outcome (ok / failed: ...) to
     * [attempts]. Re-throws on failure — callers in AUTO mode catch + roll
     * down the chain; callers in explicit mode let it propagate.
     */
    private inline fun <T> runAttempt(
        attempts: MutableList<BackendAttempt>,
        label: String,
        block: () -> T,
    ): T {
        val t0 = System.nanoTime()
        return try {
            val out = block()
            attempts += BackendAttempt(label, "ok", (System.nanoTime() - t0) / 1_000_000)
            out
        } catch (e: Throwable) {
            attempts += BackendAttempt(label, "failed: ${e.message ?: e.javaClass.simpleName}", (System.nanoTime() - t0) / 1_000_000)
            throw e
        }
    }

    /**
     * On Google Tensor, calling [Backend.CPU] or [Backend.GPU] without
     * first touching another backend reproducibly fails inside
     * `llm_litert_compiled_model_executor.cc:2023` (CPU) or SIGSEGVs in
     * `nativeCreateEngine` (GPU). Attempting [Backend.NPU] first throws
     * (no vendor delegate present) but leaves the JNI lib in a state
     * where the subsequent real init succeeds. We discard the warmup
     * engine — only the JNI side effects are wanted.
     *
     * The primer attempt is recorded as `NPU-primer` in the chain so the
     * user can see exactly what happened in `/health`.
     */
    private fun primeTensorJniState(
        modelFile: File,
        nativeLibDir: String,
        attempts: MutableList<BackendAttempt>,
    ) {
        val t0 = System.nanoTime()
        try {
            val warmupEngine = buildEngine(modelFile, /*maxTokens=*/null, Backend.NPU(nativeLibDir))
            try { warmupEngine.close() } catch (_: Throwable) {}
            attempts += BackendAttempt("NPU-primer", "ok (unexpected: closing)", (System.nanoTime() - t0) / 1_000_000)
        } catch (e: Throwable) {
            // Expected — no NPU delegate on stock Tensor. Record the
            // outcome so it's visible in /health, but don't re-throw.
            attempts += BackendAttempt("NPU-primer", "expected-fail: ${e.message ?: e.javaClass.simpleName}", (System.nanoTime() - t0) / 1_000_000)
        }
    }

    /**
     * Shared "try each backend, roll down on failure" chain used by AUTO.
     * Every attempt — including skips — is appended to [attempts] so
     * `/health` can show the exact decision tree.
     *
     * On Tensor SoCs we skip GPU explicitly rather than try-it-and-hope:
     * a GPU init crash is a native SIGSEGV that kills the process, which
     * is exactly what AUTO is supposed to prevent. Users who want to
     * test GPU on a Tensor device can pick GPU explicitly (which is the
     * informed-consent path).
     */
    private fun autoEngineChain(
        modelFile: File,
        maxTokens: Int?,
        nativeLibDir: String,
        tensor: Boolean,
        cacheKey: String,
        attempts: MutableList<BackendAttempt>,
    ): Pair<Engine, String> {
        try {
            return runAttempt(attempts, "NPU") {
                buildEngine(modelFile, maxTokens, Backend.NPU(nativeLibDir)) to "NPU"
            }
        } catch (_: Throwable) {
            LogManager.i("LLMServerService", "NPU init unavailable for $cacheKey; continuing chain")
        }
        if (tensor) {
            attempts += BackendAttempt("GPU", "skipped: known SIGSEGV on Tensor", 0L)
            LogManager.i("LLMServerService", "Skipping GPU on Tensor SoC (AUTO chain); going to CPU")
        } else {
            try {
                return runAttempt(attempts, "GPU") {
                    buildEngine(modelFile, maxTokens, Backend.GPU()) to "GPU"
                }
            } catch (_: Throwable) {
                LogManager.w("LLMServerService", "GPU init failed for $cacheKey; falling back to CPU")
            }
        }
        return runAttempt(attempts, "CPU") {
            buildEngine(modelFile, maxTokens, Backend.CPU()) to "CPU"
        }
    }

    private fun buildEngine(modelFile: File, maxTokens: Int?, backend: Backend): Engine {
        // Always enable a CPU vision backend so multimodal image inputs can be
        // served on the first request without a per-request engine rebuild.
        // The init-time cost (~hundreds of MB resident, a few hundred ms extra
        // initialize) is acceptable; not paying it would mean every first
        // image request rebuilds the engine, which is far worse UX.
        val cfg = EngineConfig(
            modelFile.absolutePath,
            backend,
            /*visionBackend=*/Backend.CPU(),
            /*audioBackend=*/null,
            /*maxNumTokens=*/maxTokens,
            /*maxNumImages=*/null,
            /*cacheDir=*/null
        )
        return Engine(cfg).also { it.initialize() }
    }

    /**
     * Engine cache lookup. Builds a new engine when this exact
     * (model, maxTokens, backend) combination isn't cached. AUTO is meaningful
     * here: try GPU, on failure log + fall back to CPU. Explicit CPU / GPU
     * choices are strict (no fallback) so the user can actually debug them.
     */
    private fun getOrCreateEngine(req: ChatRequest): EngineHandle {
        // Only honor a per-request maxTokens cap when the client explicitly
        // sent one. Otherwise pass null so LiteRT-LM uses the budget that the
        // model file was compiled with — overriding it with our generic
        // Settings.maxTokens (default 1024) is what produces the
        // DYNAMIC_UPDATE_SLICE shape mismatch on big-context Gemma 4 weights.
        val maxTokens: Int? = req.maxTokens
        val backendChoice = Settings.backend(this)
        val cacheKey = "${req.model}_${maxTokens ?: "model"}_${backendChoice}"

        engines.get(cacheKey)?.let { return EngineHandle(it.engine, cacheKey) }

        val modelFile = getModelFile(req.model)
        if (!modelFile.exists()) {
            throw IllegalStateException("Model file not found: ${modelFile.name}. Download or import it first.")
        }

        // NPU-compiled .litertlm files must be loaded with Backend.NPU. Other
        // backends either (a) cleanly fail because the weights are quantized
        // in a vendor-specific format the CPU/GPU paths can't decode, or (b)
        // SIGABRT inside `liblitertlm_jni.so` with `bad_optional_access` —
        // reproducible on Pixel 10 (LAGUNA) with the bundled Tensor dispatch
        // lib. The crash kills the whole server and erases /health, so guard
        // it here in pure Kotlin instead of relying on the native init to
        // fail gracefully. Detect via filename and the catalog SoC marker;
        // either signal is enough.
        val isNpuFile = modelFile.name.contains("-npu-") ||
            AVAILABLE_MODELS.firstOrNull { it.filename == modelFile.name }?.requiredSocMarker != null
        if (isNpuFile && backendChoice != Settings.BACKEND_NPU) {
            throw IllegalStateException(
                "Model '${req.model}' is NPU-compiled and only runs with Backend = NPU/TPU. " +
                "Open Settings → Inference → Backend, pick NPU/TPU, then try again. " +
                "Current backend: $backendChoice."
            )
        }

        LogManager.i("LLMServerService", "Loading engine for $cacheKey")
        // LiteRT-LM's NPU backend wants the path containing the vendor
        // delegate .so files (Qualcomm Hexagon, MediaTek APU, Google Edge
        // TPU). The app's own nativeLibraryDir is the right default —
        // anyone shipping a custom AAR drops the delegate there.
        val nativeLibDir = applicationInfo.nativeLibraryDir ?: ""
        val tensor = isTensorSoc()
        val attempts = mutableListOf<BackendAttempt>()
        val (engine, actualBackend) = try {
            when (backendChoice) {
                Settings.BACKEND_AUTO -> autoEngineChain(modelFile, maxTokens, nativeLibDir, tensor, cacheKey, attempts)
                Settings.BACKEND_GPU -> {
                    // Explicit GPU: no silent rerouting. On Tensor we still run
                    // the JNI primer first (the same workaround the CPU path
                    // uses — sometimes it's enough to unstick GPU too). If GPU
                    // init throws, the error propagates to the user; if it
                    // SIGSEGVs, the process dies and START_STICKY brings us
                    // back. Either way the user sees what happened.
                    if (tensor) primeTensorJniState(modelFile, nativeLibDir, attempts)
                    runAttempt(attempts, "GPU") {
                        buildEngine(modelFile, maxTokens, Backend.GPU()) to "GPU"
                    }
                }
                Settings.BACKEND_NPU -> runAttempt(attempts, "NPU") {
                    buildEngine(modelFile, maxTokens, Backend.NPU(nativeLibDir)) to "NPU"
                }
                else /* BACKEND_CPU */ -> {
                    // On Tensor SoCs, a direct Backend.CPU() init throws
                    // inside `llm_litert_compiled_model_executor.cc:2023`
                    // unless the JNI library has first attempted (and gracefully
                    // failed) some other backend. The NPU attempt below is the
                    // cheapest such warmup — without a vendor delegate it fails
                    // fast at init but leaves the JNI lib in a state where the
                    // subsequent CPU init succeeds. Reproducible on Pixel 10.
                    if (tensor) primeTensorJniState(modelFile, nativeLibDir, attempts)
                    runAttempt(attempts, "CPU") {
                        buildEngine(modelFile, maxTokens, Backend.CPU()) to "CPU"
                    }
                }
            }
        } catch (e: Exception) {
            throw IllegalStateException("Failed to initialize engine: ${e.message ?: e.javaClass.simpleName} (attempts: ${attempts.joinToString { "${it.backend}=${it.result}" }})", e)
        }

        try {
            engines.put(cacheKey, CachedEngine(engine, actualBackend, attempts.toList()))
            LogManager.i("LLMServerService", "Engine $cacheKey resolved to $actualBackend. Chain: ${attempts.joinToString { "${it.backend}(${it.result}, ${it.durationMs}ms)" }}")
        } catch (e: Exception) {
            try { engine.close() } catch (_: Exception) {}
            throw e
        }
        return EngineHandle(engine, cacheKey)
    }

    /**
     * Stable hash of `messages[0 until count]`. Used to validate that a client
     * isn't lying about conversation continuity: if their replayed prefix
     * doesn't match what we recorded, we reset the cached conversation.
     */
    /**
     * Decide whether to reuse a cached conversation or build a fresh one, and
     * compute the prompt fragment to send accordingly.
     *
     * Stateless (empty `session_id`): always a fresh conversation prefilled
     * with all-but-the-last message; caller sends the last user turn and must
     * close on exit.
     *
     * Sessioned: look up the cache. Reuse only when
     *   - sampling params match (different temperature/top_k → different conversation)
     *   - the cached prefix hash matches what the client just replayed
     *   - `messages.size > cached.seenCount`
     *   - the new range collapses to exactly one user turn after filtering out
     *     assistant turns (which are already in the KV cache) and system turns
     *     (which can't be retroactively re-bound)
     * Otherwise we rebuild from scratch.
     */
    private fun resolveSession(
        req: ChatRequest,
        handle: EngineHandle,
        temperature: Float,
        topK: Int
    ): ResolvedSession {
        val systemText = req.messages.firstOrNull { it.role == "system" }?.contentString()
        val nonSystem = req.messages.filter { it.role != "system" }
        if (nonSystem.isEmpty()) {
            throw IllegalArgumentException("Request has no non-system messages")
        }
        val last = nonSystem.last()
        if (last.role != "user" && last.role != "tool") {
            throw IllegalArgumentException("Last message must have role=user or role=tool")
        }
        val lastPrompt = apiToLlmMessage(last)
        val prior = nonSystem.dropLast(1)

        // Honor `tool_choice: "none"` by suppressing the tools list entirely;
        // the model can't invoke what it doesn't know about. `"auto"` and an
        // object-form `{type:"function", function:{name:"..."}}` both pass the
        // full set through (LiteRT-LM doesn't expose a single-tool selector,
        // so the object form degrades to "auto").
        val tools = req.tools?.takeIf {
            val choice = req.toolChoice
            !(choice != null && choice.isJsonPrimitive && choice.asJsonPrimitive.isString
                && choice.asString.equals("none", ignoreCase = true))
        }

        // Stateless path.
        if (req.sessionId.isNullOrEmpty()) {
            val conversation = createConversation(handle.engine, handle.cacheKey, temperature, topK, systemText, prior, tools)
            return ResolvedSession(
                conversation = conversation,
                prompt = lastPrompt,
                cacheKey = null,
                engineKey = handle.cacheKey,
                temperature = temperature,
                topK = topK
            )
        }

        val cacheKey = "${req.sessionId}_${handle.cacheKey}"
        val cached = sessions.get(cacheKey)

        val canReuse = cached != null &&
            cached.temperature == temperature &&
            cached.topK == topK &&
            cached.seenCount < req.messages.size &&
            cached.prefixHash == messagesPrefixHash(req.messages, cached.seenCount) &&
            run {
                // The "new range" since the cached conversation last saw the client.
                // Reuse is only safe when this contains exactly one *driving* turn
                // (a user turn or a tool follow-up); the rest must be assistant
                // turns the engine already has in its KV cache. Tool definitions
                // can't be changed mid-conversation either — if the new request
                // has tools and the cached conversation didn't (or vice versa)
                // we don't bother trying to detect that here; a sampling-param
                // change is the common case for "client changed semantics" and
                // we just rebuild. (Tool-set changes will land in a later stage.)
                val newRange = req.messages.subList(cached.seenCount, req.messages.size)
                val driving = newRange.filter { it.role != "assistant" }
                driving.size == 1 && (driving[0].role == "user" || driving[0].role == "tool")
            }

        if (canReuse) {
            cached!!
            val newDriving = req.messages.subList(cached.seenCount, req.messages.size)
                .first { it.role != "assistant" }
            LogManager.i("LLMServerService", "Session $cacheKey reused (sending 1 new ${newDriving.role} turn)")
            return ResolvedSession(
                conversation = cached.conversation,
                prompt = apiToLlmMessage(newDriving),
                cacheKey = cacheKey,
                engineKey = handle.cacheKey,
                temperature = temperature,
                topK = topK
            )
        }

        // Rebuild path — either no cache, sampling params changed, prefix
        // mismatched, or the client added something we can't merge in-place.
        if (cached != null) sessions.remove(cacheKey)
        val conversation = createConversation(handle.engine, handle.cacheKey, temperature, topK, systemText, prior, tools)
        return ResolvedSession(
            conversation = conversation,
            prompt = lastPrompt,
            cacheKey = cacheKey,
            engineKey = handle.cacheKey,
            temperature = temperature,
            topK = topK
        )
    }

    /**
     * Call on successful generation. Stores or updates the conversation in cache so
     * the next request for this session_id can pick up where we left off.
     * No-op for stateless conversations — caller must close those explicitly.
     */
    private fun commitSession(resolved: ResolvedSession, messages: List<Message>) {
        val cacheKey = resolved.cacheKey ?: return
        sessions.put(cacheKey, CachedSession(
            conversation = resolved.conversation,
            engineKey = resolved.engineKey,
            temperature = resolved.temperature,
            topK = resolved.topK,
            prefixHash = messagesPrefixHash(messages, messages.size),
            seenCount = messages.size,
            createdAt = sessions.get(cacheKey)?.createdAt ?: System.currentTimeMillis()
        ))
        // Marker so the next createConversation on this engine knows what's alive.
        activeConversations[resolved.engineKey] = resolved.conversation
    }

    /**
     * Call on failure to drop a (possibly half-initialized) conversation from cache.
     * Closes the conversation as a side effect.
     */
    private fun invalidateSession(resolved: ResolvedSession) {
        activeConversations.remove(resolved.engineKey, resolved.conversation)
        val cacheKey = resolved.cacheKey
        if (cacheKey != null) {
            sessions.remove(cacheKey)
        } else {
            try { resolved.conversation.close() } catch (_: Exception) {}
        }
    }

    /** Stateless cleanup helper. */
    private fun closeIfStateless(resolved: ResolvedSession) {
        if (!resolved.isCached) {
            activeConversations.remove(resolved.engineKey, resolved.conversation)
            try { resolved.conversation.close() } catch (_: Exception) {}
        }
    }

    override fun onDestroy() {
        ServerState.setStatus(ServerState.Status.STOPPED)
        ServerState.setBoundUrl(null)
        idleMonitorJob?.cancel()
        serviceScope.cancel()
        try { if (wakeLock.isHeld) wakeLock.release() } catch (_: Exception) {}
        try {
            server?.stop(500, 1000)
            server = null
        } catch (e: Exception) {
            LogManager.e("LLMServerService", "Error stopping server", e)
        }
        // Close conversations before engines — conversations reference engines and must
        // not outlive them.
        sessions.evictAll()
        engines.evictAll()
        embeddings.evictAll()
        documentStoreRef?.let {
            try { it.close() } catch (_: Exception) {}
            documentStoreRef = null
        }
        try {
            kotlinx.coroutines.runBlocking { RequestTracker.resetAll() }
        } catch (_: Exception) {}
        LogManager.i("LLMServerService", "Server stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_STOP = "com.localllm.app.ACTION_STOP"
    }
}
