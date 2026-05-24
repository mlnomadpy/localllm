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
import com.localllm.app.inference.EmbeddingRegistry
import com.localllm.app.inference.EngineRegistry
import com.localllm.app.inference.litert.SessionManager
import com.localllm.app.rag.DocumentStore
import com.localllm.app.server.ServerDeps
import com.localllm.app.server.ServerEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.netty.NettyApplicationEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.atomic.AtomicLong

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

/**
 * Foreground service that hosts the OpenAI-compatible HTTP API.
 *
 * Lifecycle only. All routing logic lives in [com.localllm.app.server], all
 * inference logic in [com.localllm.app.inference]; this class owns the
 * foreground notification, the Ktor [EmbeddedServer] handle, the
 * [ServerDeps] bundle, the idle-eviction loop, the wake lock, the
 * [onTrimMemory] policy, and the boot/start/stop wiring.
 */
class LLMServerService : Service() {

    private var server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>? = null

    /** Inference mutex — only LiteRT engines need it (AICore is system-serialized). */
    private val inferenceMutex = Mutex()

    /** Lazy doc store; opened on first /v1/documents or /v1/search request. */
    @Volatile private var documentStoreRef: DocumentStore? = null
    private fun documentStore(): DocumentStore {
        documentStoreRef?.let { return it }
        synchronized(this) {
            documentStoreRef?.let { return it }
            val s = DocumentStore(this)
            documentStoreRef = s
            return s
        }
    }

    private val rateLimiter = RateLimiter(
        ratePerSec = Settings.DEFAULT_RATE_LIMIT_PER_SEC,
        burst = Settings.DEFAULT_RATE_LIMIT_BURST,
    )

    private val lastActivityAt = AtomicLong(System.currentTimeMillis())

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Default + serviceJob)
    private var idleMonitorJob: Job? = null

    /** Partial wake lock — only held while inference is active. */
    private val wakeLock by lazy {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LocalLLM:Inference").apply {
            setReferenceCounted(false)
        }
    }

    private val engineRegistry by lazy { EngineRegistry(this) }
    private val embeddingRegistry by lazy { EmbeddingRegistry(this) }
    private val sessionManager by lazy { SessionManager(engineRegistry) }

    private val deps by lazy {
        ServerDeps(
            appContext = this,
            engineRegistry = engineRegistry,
            sessionManager = sessionManager,
            embeddingRegistry = embeddingRegistry,
            documentStore = ::documentStore,
            inferenceMutex = inferenceMutex,
            rateLimiter = rateLimiter,
            serviceScope = serviceScope,
            lastActivityAt = lastActivityAt,
            acquireWakeLock = ::withWakeLock,
        )
    }

    override fun onCreate() {
        super.onCreate()
        startForeground()
        startServer()
        startIdleMonitor()
    }

    private fun startIdleMonitor() {
        idleMonitorJob?.cancel()
        idleMonitorJob = serviceScope.launch {
            while (isActive) {
                delay(30_000L)
                val idleMs = System.currentTimeMillis() - lastActivityAt.get()
                val evictAfter = Settings.idleEvictMs(this@LLMServerService)
                val stopAfter = Settings.idleStopMs(this@LLMServerService)

                if (evictAfter > 0 && idleMs >= evictAfter && engineRegistry.engineCount() > 0) {
                    if (inferenceMutex.tryLock()) {
                        try {
                            val n = engineRegistry.evictAllLiteRt()
                            if (n > 0) LogManager.i(
                                "LLMServerService",
                                "Idle eviction: released $n engine(s) after ${idleMs / 1000}s idle",
                            )
                        } finally {
                            inferenceMutex.unlock()
                        }
                    }
                }
                if (evictAfter > 0 && idleMs >= evictAfter && embeddingRegistry.size() > 0) {
                    val n = embeddingRegistry.evictAll()
                    LogManager.i(
                        "LLMServerService",
                        "Idle eviction: released $n embedding model(s) after ${idleMs / 1000}s idle",
                    )
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
        // START_STICKY: when the OS kills us under memory pressure (LMK)
        // Android re-creates the service with a null intent. That's fine —
        // onCreate unconditionally calls startServer + startIdleMonitor.
        return START_STICKY
    }

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
                            val before = engineRegistry.engineCount()
                            if (before > 1) {
                                val dropped = engineRegistry.trimLiteRtTo(1)
                                LogManager.i(
                                    "MemoryPressure",
                                    "trim level=$level, action=shrunk LRU by $dropped (was $before)",
                                )
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
                            val nEngines = engineRegistry.engineCount()
                            val nSessions = sessionManager.size()
                            val nEmb = embeddingRegistry.size()
                            // Sessions reference engines; evict them first so
                            // the engine eviction callback doesn't double-close.
                            sessionManager.evictAll()
                            engineRegistry.evictAllLiteRt()
                            embeddingRegistry.evictAll()
                            LogManager.i(
                                "MemoryPressure",
                                "trim level=$level, action=evicted all ($nEngines engines, $nSessions sessions, $nEmb embedding models)",
                            )
                        } finally {
                            inferenceMutex.unlock()
                        }
                    } else {
                        LogManager.w(
                            "MemoryPressure",
                            "trim level=$level, action=could-not-acquire-lock (inference active; LMK may kill us)",
                        )
                    }
                }
            }
            ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> {
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
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, LLMServerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
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
            server = ServerEngine.build(deps, port, host).also { it.start(wait = false) }
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
     * Hold a partial wake lock for the duration of [block]. The lock has a
     * hard timeout slightly larger than the inference budget so a buggy code
     * path can't drain the battery forever. When [Settings.keepAwake] is
     * off, this is a passthrough.
     */
    private suspend fun withWakeLock(timeoutMs: Long, block: suspend () -> Unit) {
        val enabled = Settings.keepAwake(this)
        if (!enabled) { block(); return }
        @Suppress("WakelockTimeout")
        wakeLock.acquire(timeoutMs + 5_000L)
        try {
            block()
        } finally {
            try { if (wakeLock.isHeld) wakeLock.release() } catch (_: Exception) {}
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
        // Sessions reference engines; close them first.
        sessionManager.evictAll()
        engineRegistry.evictAllLiteRt()
        embeddingRegistry.evictAll()
        documentStoreRef?.let {
            try { it.close() } catch (_: Exception) {}
            documentStoreRef = null
        }
        try {
            runBlocking { RequestTracker.resetAll() }
        } catch (_: Exception) {}
        LogManager.i("LLMServerService", "Server stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_STOP = "com.localllm.app.ACTION_STOP"
    }
}
