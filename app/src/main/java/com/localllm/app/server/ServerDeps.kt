package com.localllm.app.server

import android.content.Context
import com.localllm.app.RateLimiter
import com.localllm.app.inference.EmbeddingRegistry
import com.localllm.app.inference.EngineRegistry
import com.localllm.app.inference.litert.SessionManager
import com.localllm.app.rag.DocumentStore
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.sync.Mutex

/**
 * Bundle of process-scoped dependencies the route layer needs. Created once
 * by [LLMServerService] and passed by reference into every route extension.
 * Keeping the routes parameter-driven (rather than tied to the Service
 * class) is what makes the FSD split meaningful — routes become drop-in,
 * testable units instead of methods on a 2000-line god object.
 */
class ServerDeps(
    val appContext: Context,
    val engineRegistry: EngineRegistry,
    val sessionManager: SessionManager,
    val embeddingRegistry: EmbeddingRegistry,
    val documentStore: () -> DocumentStore,
    val inferenceMutex: Mutex,
    val rateLimiter: RateLimiter,
    val serviceScope: CoroutineScope,
    val lastActivityAt: AtomicLong,
    val acquireWakeLock: suspend (timeoutMs: Long, block: suspend () -> Unit) -> Unit,
)
