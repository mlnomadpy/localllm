package com.localllm.app

import android.app.Application
import android.os.StrictMode
import com.localllm.app.warmup.WarmupWorker

/**
 * Single Application entry point. Installs a global uncaught exception logger
 * so crashes surface in the Console tab instead of vanishing into the system
 * log, enables StrictMode in debug builds, and forwards memory-pressure
 * callbacks through [LogManager] for visibility.
 *
 * This is the natural place to wire in real crash reporting (Sentry /
 * Crashlytics), DI, or a logging library later.
 */
class LocalLLMApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // StrictMode in debug builds only. We deliberately avoid `penaltyDeath`:
        // the goal is to surface accidental disk / network I/O on the main
        // thread without crashing the dev build. The first run will produce
        // some unfixable framework-level noise (SharedPreferences init,
        // Compose system reads) — those are expected. We care about our own
        // inference / service code paths.
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder()
                    .detectDiskReads()
                    .detectDiskWrites()
                    .detectNetwork()
                    .penaltyLog()
                    .build()
            )
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder()
                    .detectLeakedClosableObjects()
                    .detectLeakedRegistrationObjects()
                    .detectActivityLeaks()
                    .penaltyLog()
                    .build()
            )
        }

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, ex ->
            try {
                LogManager.e("CRASH", "Uncaught on ${thread.name}", ex)
            } catch (_: Throwable) {
                // Never swallow the crash because we couldn't log it.
            }
            previous?.uncaughtException(thread, ex)
        }

        LogManager.i("App", "LocalLLM v${BuildConfig.VERSION_NAME} starting")

        // The background warm-up job opened its own engine and could leave the
        // native LiteRT runtime in a contested state vs the foreground service
        // (status 13 - "failed to invoke compiled model" on the next service
        // request). The fix is in WarmupWorker.doWork (skip when service is up
        // + close its registry in finally). We do NOT re-schedule it from app
        // start: warm-up is an optimisation, and we'd rather have reliable
        // chat than a 2-3s first-token improvement.
        // To re-enable cleanly we need a process-wide EngineRegistry instead
        // of one-per-component.
        try {
            WarmupWorker.cancel(this)
        } catch (t: Throwable) {
            LogManager.w("App", "Failed to cancel WarmupWorker: ${t.message}")
        }
    }

    /**
     * Application also receives memory-pressure callbacks independently of the
     * Service (Android dispatches to every registered [android.content.ComponentCallbacks2]).
     * We log here so the Console tab shows the event even before the Service
     * sees it, and rely on [LLMServerService.onTrimMemory] to do the actual
     * eviction work. We intentionally do NOT try to share state through the
     * Application class — that would couple Service lifecycle to a global.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        LogManager.i("MemoryPressure", "Application.onTrimMemory level=$level")
    }
}
