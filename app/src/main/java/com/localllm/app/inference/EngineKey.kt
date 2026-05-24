package com.localllm.app.inference

import com.localllm.app.Backend

/**
 * Typed cache key for a LiteRT-LM engine instance. Replaces the ad-hoc
 * `"${modelId}_${maxTokens ?: "model"}_${backend.name}"` string that used to
 * be scattered across [EngineRegistry] and [com.localllm.app.inference.litert.SessionManager].
 *
 * Kotlin data-class equality is used by [android.util.LruCache] and by
 * [java.util.concurrent.ConcurrentHashMap] so no manual hashCode/equals
 * override is needed.
 *
 * @property modelId   Catalog model id (or side-loaded filename stem).
 * @property maxTokens KV-cache budget passed to `EngineConfig.maxNumTokens`;
 *                     `null` means "let the model's bundle decide".
 * @property backend   Hardware backend declared in the catalog for this model.
 */
data class EngineKey(
    val modelId: String,
    val maxTokens: Int?,   // null means "model default"
    val backend: Backend,
) {
    /** Stable string form used for logs and the `/health` `key` field. */
    fun asString(): String = "${modelId}_${maxTokens ?: "model"}_${backend.name}"
}
