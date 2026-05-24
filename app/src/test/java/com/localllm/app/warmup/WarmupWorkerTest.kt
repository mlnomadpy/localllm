package com.localllm.app.warmup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.localllm.app.Settings
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit-test the WarmupWorker against its observable contracts:
 *   - the skip path (autostart + boot-start both off) → Result.success()
 *   - the failure-swallow path (missing model file)  → Result.success()
 *   - schedule(context) enqueues a unique periodic work item
 *   - cancel(context) removes that work item
 *
 * The actual warmup() inner body needs LiteRT / AICore native code, so we
 * never reach the real engine path under Robolectric; the worker's outer
 * catch swallows the IllegalStateException ("Model file not found") and we
 * assert the outer result.
 */
@RunWith(RobolectricTestRunner::class)
class WarmupWorkerTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        Settings.prefs(context).edit().clear().apply()
        val config = Configuration.Builder()
            .setExecutor(SynchronousExecutor())
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
    }

    @Test
    fun `skips when both autostart and startOnBoot are disabled`() = runTest {
        Settings.setAutostart(context, false)
        Settings.setStartOnBoot(context, false)
        val worker = TestListenableWorkerBuilder<WarmupWorker>(context).build()
        val result = worker.doWork()
        assertEquals(ListenableWorker.Result.success(), result)
    }

    @Test
    fun `returns success not retry when the selected model file is missing`() = runTest {
        // Defaults: autostart=true, startOnBoot=true — past the skip guard.
        // Pick a model id the catalog routes to LiteRT — acquire() throws
        // "Model file not found" because no .litertlm exists on disk.
        Settings.setSelectedModelId(context, "side-loaded-bogus-model-id")
        val worker = TestListenableWorkerBuilder<WarmupWorker>(context).build()
        val result = worker.doWork()
        // The catch-all in doWork() swallows the throw and returns success
        // so the periodic schedule keeps the next window. It must NOT be
        // Result.retry() — retries would burn the WorkManager backoff budget.
        assertEquals(ListenableWorker.Result.success(), result)
        assertNotEquals(ListenableWorker.Result.retry(), result)
    }

    @Test
    fun `schedule enqueues a periodic work item under the unique name`() {
        WarmupWorker.schedule(context)
        val infos = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork("localllm.warmup")
            .get()
        assertEquals(1, infos.size)
        val info = infos[0]
        assertTrue(
            "expected ENQUEUED or RUNNING, got ${info.state}",
            info.state == WorkInfo.State.ENQUEUED || info.state == WorkInfo.State.RUNNING,
        )
    }

    @Test
    fun `schedule called twice keeps the same instance (KEEP policy)`() {
        WarmupWorker.schedule(context)
        val firstId = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork("localllm.warmup")
            .get()
            .single()
            .id
        // Second schedule must be a no-op under ExistingPeriodicWorkPolicy.KEEP.
        WarmupWorker.schedule(context)
        val secondId = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork("localllm.warmup")
            .get()
            .single()
            .id
        assertEquals("KEEP must preserve the original work id", firstId, secondId)
    }

    @Test
    fun `cancel removes the previously scheduled work`() {
        WarmupWorker.schedule(context)
        WarmupWorker.cancel(context)
        val infos = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork("localllm.warmup")
            .get()
        // After cancel: either no work at all, or work transitioned to CANCELLED.
        if (infos.isNotEmpty()) {
            assertEquals(WorkInfo.State.CANCELLED, infos[0].state)
        }
    }

    @Test
    fun `cancel with no prior schedule is a no-op`() {
        // Just exercise the code path — must not throw.
        WarmupWorker.cancel(context)
        val infos = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork("localllm.warmup")
            .get()
        assertEquals(0, infos.size)
    }
}
