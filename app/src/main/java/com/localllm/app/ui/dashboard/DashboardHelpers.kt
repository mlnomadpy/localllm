package com.localllm.app.ui.dashboard

internal fun formatMs(ms: Long): String = when {
    ms < 1000 -> "$ms ms"
    ms < 60_000 -> "%.1fs".format(ms / 1000f)
    else -> "${ms / 60_000}m ${(ms % 60_000) / 1000}s"
}

internal fun relativeTime(timestamp: Long): String {
    val delta = (System.currentTimeMillis() - timestamp) / 1000
    return when {
        delta < 5 -> "just now"
        delta < 60 -> "${delta}s ago"
        delta < 3600 -> "${delta / 60}m ago"
        delta < 86400 -> "${delta / 3600}h ago"
        else -> "${delta / 86400}d ago"
    }
}
