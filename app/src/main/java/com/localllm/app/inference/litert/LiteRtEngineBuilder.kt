package com.localllm.app.inference.litert

import com.google.ai.edge.litertlm.Backend as LiteRtBackend
import com.google.ai.edge.litertlm.Engine as LiteRtNativeEngine
import com.google.ai.edge.litertlm.EngineConfig
import com.localllm.app.Backend
import com.localllm.app.LogManager
import java.io.File

/**
 * Constructs LiteRT-LM engines on the catalog-declared backend. No fallback,
 * no chain — if the requested backend can't initialize, the failure
 * propagates verbatim. The replaced AUTO chain hid these errors behind a
 * silent fall-through; this version surfaces them so the user can act on a
 * misconfigured model or device.
 *
 * Tensor-SoC primer: on Google Tensor (Pixel 6+), a direct CPU init can
 * SIGABRT inside `llm_litert_compiled_model_executor.cc:2023` unless the JNI
 * library has first attempted (and gracefully failed) some other backend.
 * The NPU attempt below is the cheapest such warmup — without a vendor
 * delegate it fails fast at init but leaves the JNI lib in a state where the
 * subsequent CPU init succeeds. Reproducible on Pixel 10.
 */
object LiteRtEngineBuilder {

    fun build(
        modelFile: File,
        maxTokens: Int?,
        backend: Backend,
        nativeLibDir: String,
    ): LiteRtNativeEngine {
        require(backend != Backend.AICORE) { "AICORE is not a LiteRT backend" }
        if (TensorSoCDetector.isTensorSoc() &&
            (backend == Backend.LITERT_CPU || backend == Backend.LITERT_GPU)
        ) {
            primeTensorJniState(modelFile, nativeLibDir)
        }
        val native = backend.toLiteRt(nativeLibDir)
        return buildOne(modelFile, maxTokens, native)
    }

    private fun Backend.toLiteRt(nativeLibDir: String): LiteRtBackend = when (this) {
        Backend.LITERT_CPU -> LiteRtBackend.CPU()
        Backend.LITERT_GPU -> LiteRtBackend.GPU()
        Backend.LITERT_NPU -> LiteRtBackend.NPU(nativeLibDir)
        Backend.AICORE     -> error("AICORE is not a LiteRT backend")
    }

    private fun buildOne(
        modelFile: File,
        maxTokens: Int?,
        backend: LiteRtBackend,
    ): LiteRtNativeEngine {
        // Always enable a CPU vision backend so multimodal image inputs can
        // be served on the first request without a per-request engine
        // rebuild. The init-time cost (~hundreds of MB resident, a few
        // hundred ms extra initialize) is acceptable; not paying it would
        // mean every first image request rebuilds the engine.
        val cfg = EngineConfig(
            modelFile.absolutePath,
            backend,
            /*visionBackend=*/LiteRtBackend.CPU(),
            /*audioBackend=*/null,
            /*maxNumTokens=*/maxTokens,
            /*maxNumImages=*/null,
            /*cacheDir=*/null,
        )
        return LiteRtNativeEngine(cfg).also { it.initialize() }
    }

    /**
     * On Google Tensor, calling [LiteRtBackend.CPU] or [LiteRtBackend.GPU]
     * without first touching another backend reproducibly fails inside
     * `llm_litert_compiled_model_executor.cc:2023` (CPU) or SIGSEGVs in
     * `nativeCreateEngine` (GPU). Attempting [LiteRtBackend.NPU] first
     * throws (no vendor delegate present) but leaves the JNI lib in a
     * state where the subsequent real init succeeds. We discard the warmup
     * engine — only the JNI side effects are wanted.
     */
    private fun primeTensorJniState(modelFile: File, nativeLibDir: String) {
        try {
            val warmup = buildOne(modelFile, /*maxTokens=*/null, LiteRtBackend.NPU(nativeLibDir))
            try { warmup.close() } catch (_: Throwable) {}
            LogManager.i("LiteRtEngineBuilder", "Tensor JNI primer ran (unexpectedly succeeded; engine discarded)")
        } catch (e: Throwable) {
            // Expected — no NPU delegate on stock Tensor. The primer side
            // effects on the JNI library are what we want.
            LogManager.i("LiteRtEngineBuilder", "Tensor JNI primer ran (expected NPU fail: ${e.message ?: e.javaClass.simpleName})")
        }
    }
}
