package com.localllm.app.download

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import com.localllm.app.ModelInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Per-file download progress snapshot — kept as a plain data class so
 * callers can map it onto whatever Compose state they already maintain.
 */
data class DownloadStatus(
    val filename: String,
    val progress: Float,
    val totalBytes: Long,
    val status: State,
) {
    enum class State {
        IN_PROGRESS,
        SUCCESSFUL,
        FAILED,
        GONE, // DownloadManager has no row for this id (cancelled / wiped)
    }
}

/**
 * Thin wrapper around the system [DownloadManager]. Owns no state of its
 * own — the caller is responsible for keeping track of which downloads
 * are active (filename → downloadId). This mirrors the inline polling
 * loop that used to live in MainActivity.
 */
class DownloadRepository(private val context: Context) {

    private val manager: DownloadManager
        get() = context.getSystemService(DownloadManager::class.java)

    /**
     * Enqueue a download to the app's external files dir using the same
     * notification visibility / metered / roaming policy MainActivity
     * historically used.
     */
    fun enqueue(model: ModelInfo): Long {
        val dest = java.io.File(context.getExternalFilesDir(null), model.filename)
        dest.parentFile?.mkdirs()
        val request = DownloadManager.Request(Uri.parse(model.url))
            .setTitle("Downloading ${model.name}")
            .setDescription("Required for LocalLLM Service")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, null, model.filename)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)
        return manager.enqueue(request)
    }

    /** Cancel and remove a download row. */
    fun cancel(downloadId: Long) {
        manager.remove(downloadId)
    }

    /** One-shot query of the current state of [downloadId] for [filename]. */
    fun queryOnce(filename: String, downloadId: Long): DownloadStatus {
        val cursor = manager.query(DownloadManager.Query().setFilterById(downloadId))
        cursor.use { c ->
            if (!c.moveToFirst()) {
                return DownloadStatus(filename, 0f, 0L, DownloadStatus.State.GONE)
            }
            val statusIdx = c.getColumnIndex(DownloadManager.COLUMN_STATUS)
            val downloadedIdx = c.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
            val totalIdx = c.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
            val status = if (statusIdx != -1) c.getInt(statusIdx) else 0
            val downloaded = if (downloadedIdx != -1) c.getLong(downloadedIdx) else 0L
            val total = if (totalIdx != -1) c.getLong(totalIdx) else 0L
            val progress = if (total > 0) downloaded.toFloat() / total else 0f
            val mapped = when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> DownloadStatus.State.SUCCESSFUL
                DownloadManager.STATUS_FAILED -> DownloadStatus.State.FAILED
                else -> DownloadStatus.State.IN_PROGRESS
            }
            return DownloadStatus(filename, progress, total, mapped)
        }
    }

    /**
     * Emit a [DownloadStatus] for every active row in [active] (a live
     * filename → downloadId map) every [intervalMs]. The flow runs until
     * [active] becomes empty or the collector cancels.
     *
     * Note: [active] is sampled by reference on each tick — pass a Compose
     * `mutableStateMapOf` (or any other observable Map) that the caller
     * mutates as terminal states are observed. Callers should remove
     * entries from [active] once they see `SUCCESSFUL`, `FAILED`, or
     * `GONE` to stop further polling for that filename.
     */
    fun pollAll(
        active: Map<String, Long>,
        intervalMs: Long = 1_000L,
    ): Flow<List<DownloadStatus>> = flow {
        while (active.isNotEmpty()) {
            val snapshot = active.toMap().map { (name, id) -> queryOnce(name, id) }
            emit(snapshot)
            delay(intervalMs)
        }
    }
}
