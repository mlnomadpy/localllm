package com.localllm.app.warmup

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.ai.edge.litertlm.Message as LlmMessage
import com.localllm.app.LogManager
import com.localllm.app.Settings
import com.localllm.app.inference.AiCoreNotReadyException
import com.localllm.app.inference.EngineRegistry
import com.localllm.app.inference.aicore.AICoreEngine
import com.localllm.app.inference.litert.LlmMessageConverter
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Periodic background warm-up. Runs when the device is idle and battery is
 * not low; pre-loads the user's selected engine so the first foreground
 * request after a long pause doesn't pay the full ~3–15s LiteRT init or
 * trigger an AICore on-demand download.
 *
 * The worker is intentionally short-lived: acquire the engine, run a
 * 1-token generation (LiteRT) or status probe (AICore), and exit. The
 * engine then sits in the [EngineRegistry] LRU. Subsequent user requests
 * hit the cached entry.
 *
 * Skipped when the user has disabled the foreground service entirely
 * (`Settings.autostart=false` AND `Settings.startOnBoot=false`) — there's
 * no point keeping the engine resident if the server isn't going to run.
 */
class WarmupWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        if (!Settings.autostart(ctx) && !Settings.startOnBoot(ctx)) {
            LogManager.i(TAG, "Skipped — server autostart and boot-start both disabled")
            return Result.success()
        }
        // Don't compete with the foreground service for the model file. The
        // service has its own EngineRegistry; if it's running, an engine is
        // either already cached there or about to be loaded on the next user
        // request. Loading our own copy in this worker leaves a second native
        // engine pinned against the same .litertlm bundle, which on LiteRT-LM
        // surfaces as "status 13 - failed to invoke the compiled model" the
        // next time the service tries to use it.
        if (com.localllm.app.ServerState.status.value != com.localllm.app.ServerState.Status.STOPPED) {
            LogManager.i(TAG, "Skipped — service is up; warm-up would contend for the native engine")
            return Result.success()
        }
        val modelId = Settings.selectedModelId(ctx)
        val registry = EngineRegistry(ctx)
        val t0 = System.nanoTime()
        return try {
            withTimeout(WARMUP_TIMEOUT_MS) { warmup(registry, modelId) }
            val ms = (System.nanoTime() - t0) / 1_000_000
            LogManager.i(TAG, "Warmed $modelId in ${ms}ms")
            Result.success()
        } catch (_: TimeoutCancellationException) {
            LogManager.w(TAG, "Warm-up timed out for $modelId after ${WARMUP_TIMEOUT_MS}ms")
            Result.retry()
        } catch (e: Throwable) {
            LogManager.w(TAG, "Warm-up failed for $modelId: ${e.message ?: e.javaClass.simpleName}")
            // Failure here is expected on devices where AICore is unavailable
            // or the selected model file is missing. Don't burn retries — the
            // periodic schedule will pick the next window.
            Result.success()
        } finally {
            // Close every native engine this worker opened. Without this the
            // engine sits in registry's LRU but `registry` goes out of scope
            // when doWork returns; the native handle stays alive (no finalizer)
            // and contests the model file with the foreground service.
            try { registry.evictAllLiteRt() } catch (_: Throwable) {}
        }
    }

    private suspend fun warmup(registry: EngineRegistry, modelId: String) {
        when (val acquired = registry.acquire(modelId, /*maxTokens=*/null)) {
            is EngineRegistry.AcquiredEngine.AiCore -> {
                try {
                    registry.ensureAiCoreReady()
                } catch (_: AiCoreNotReadyException) {
                    return
                }
                AICoreEngine.complete(prompt = "Hi", maxOutputTokens = 1)
            }
            is EngineRegistry.AcquiredEngine.LiteRt -> {
                val conv = LlmMessageConverter.createConversation(
                    engine = acquired.engine.native,
                    temperature = 0f,
                    topK = 1,
                    systemText = null,
                    initial = emptyList(),
                    tools = null,
                )
                try {
                    withContext(Dispatchers.Default) {
                        conv.sendMessage(LlmMessage.user("Hi"), emptyMap())
                    }
                } finally {
                    try { conv.close() } catch (_: Exception) {}
                }
            }
        }
    }

    companion object {
        private const val TAG = "WarmupWorker"
        private const val WORK_NAME = "localllm.warmup"
        private const val WARMUP_TIMEOUT_MS = 90_000L

        /**
         * Schedule (or replace) the periodic warm-up job. Safe to call
         * multiple times — KEEP policy treats the second-and-later calls as
         * no-ops, so app startup can call this unconditionally without
         * stomping a prior schedule.
         *
         * Frequency: 6 hours. This is a compromise — short enough that an
         * abandoned-then-resumed install still benefits, long enough that
         * the engine doesn't keep getting re-loaded under a memory-pressure
         * eviction loop.
         */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .setRequiresDeviceIdle(true)
                .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                .build()
            val req = PeriodicWorkRequestBuilder<WarmupWorker>(6, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                req,
            )
        }

        /** Cancel the periodic job — used when the user disables warm-up. */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
