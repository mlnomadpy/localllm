package com.localllm.app.ui.chat

import com.localllm.app.AVAILABLE_MODELS

/**
 * Translate a bare model filename (or id) into a friendly label using the
 * built-in [AVAILABLE_MODELS] catalog. Custom / unknown models fall back to
 * the bare filename so the user still sees a stable identifier.
 */
fun displayLabelFor(filename: String): String {
    val bare = filename.removeSuffix(".litertlm").removeSuffix(".task")
    val known = AVAILABLE_MODELS.firstOrNull { it.id == bare }
    return known?.name ?: bare
}
