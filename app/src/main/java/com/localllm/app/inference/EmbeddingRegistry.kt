package com.localllm.app.inference

import android.content.Context
import android.util.LruCache
import com.localllm.app.LogManager
import com.localllm.app.embedding.EmbeddingService
import java.io.File

/**
 * LRU cache of [EmbeddingService] instances. Bounded at 1 — embedding models
 * are small (~127 MB for bge-small) but each ORT session still holds
 * non-trivial RAM, and we never need two concurrent embedding models.
 *
 * Vocab discovery uses three conventional filename patterns (`-vocab.txt`,
 * `.vocab.txt`, `_vocab.txt`); the model is considered unavailable if none
 * are present next to the `.onnx`.
 */
class EmbeddingRegistry(private val appContext: Context) {

    private val cache = object : LruCache<String, EmbeddingService>(1) {
        override fun entryRemoved(
            evicted: Boolean,
            key: String?,
            oldValue: EmbeddingService?,
            newValue: EmbeddingService?,
        ) {
            if (oldValue != null && oldValue !== newValue) {
                try { oldValue.close() } catch (_: Exception) {}
                if (evicted) LogManager.i("EmbeddingRegistry", "Evicted embedding model: $key")
            }
        }
    }

    fun resolveVocabFor(modelId: String): File? {
        val dir = appContext.getExternalFilesDir(null) ?: return null
        return listOf(
            File(dir, "$modelId-vocab.txt"),
            File(dir, "$modelId.vocab.txt"),
            File(dir, "${modelId}_vocab.txt"),
        ).firstOrNull { it.exists() }
    }

    fun acquire(modelId: String): EmbeddingService {
        cache.get(modelId)?.let { return it }
        val dir = appContext.getExternalFilesDir(null) ?: error("external files dir unavailable")
        val modelFile = File(dir, "$modelId.onnx")
        if (!modelFile.exists()) error("model file ${modelFile.name} not found")
        val vocabFile = resolveVocabFor(modelId)
            ?: error("vocab file for '$modelId' not found (expected $modelId-vocab.txt)")
        val svc = EmbeddingService(
            modelPath = modelFile.absolutePath,
            vocabPath = vocabFile.absolutePath,
        )
        cache.put(modelId, svc)
        return svc
    }

    fun evictAll(): Int {
        val n = cache.size()
        cache.evictAll()
        return n
    }

    fun size(): Int = cache.size()
}
