package com.localllm.app

import android.os.Build

/**
 * Metadata for a downloadable / importable model bundle. Used both by the
 * Catalog tab (built-in entries) and to render user-added URLs as catalog rows.
 */
data class ModelInfo(
    val id: String,
    val name: String,
    val description: String,
    val url: String,
    val filename: String,
    /**
     * Lowercase hex SHA-256 of the file at [url], if known. The download flow
     * verifies it post-completion and deletes the file on mismatch. `null`
     * means "skip verification" — that's intentional for custom URLs where
     * we don't have a hash to compare against.
     */
    val sha256: String? = null,
    /**
     * Lowercase SoC marker required by an NPU-compiled `.litertlm`
     * (`sm8750`, `mt6989`, …). Null for CPU/GPU-portable models. When set,
     * the model only runs end-to-end on a device whose
     * [Build.SOC_MODEL] contains this marker AND has a vendor NPU delegate
     * (QAIRT for Qualcomm, NeuroPilot for MediaTek) reachable via
     * `nativeLibraryDir` — see [Settings.hasNpuDelegate].
     */
    val requiredSocMarker: String? = null,
    /**
     * Virtual entries are served by something other than a `.litertlm`
     * file on disk — e.g. AICore (Gemini Nano) goes through the ML Kit
     * Prompt API. Empty [url] / [filename], no Download/Delete UI, and
     * the Models tab renders a static "provided by X" chip instead.
     * Always considered "available" at the catalog level; per-request
     * availability is the engine's problem.
     */
    val isVirtual: Boolean = false,
)

/**
 * Human-readable SoC label for [ModelInfo.requiredSocMarker], or `null` when
 * the model isn't NPU-gated. Used in the Catalog UI chip.
 */
fun ModelInfo.npuSocLabel(): String? = when (requiredSocMarker?.lowercase()) {
    "sm8550" -> "Snapdragon 8 Gen 2 (SM8550)"
    "sm8650" -> "Snapdragon 8 Gen 3 (SM8650)"
    "sm8750" -> "Snapdragon 8 Elite (SM8750)"
    "sm8850" -> "Snapdragon 8 Elite Gen 5 (SM8850)"
    "mt6989" -> "Dimensity 9300 (MT6989)"
    "mt6991" -> "Dimensity 9400 (MT6991)"
    "mt6993" -> "Dimensity 9500 (MT6993)"
    "laguna" -> "Google Tensor G5 (Pixel 10)"
    null -> null
    else -> requiredSocMarker.uppercase()
}

/**
 * Returns true if the current device's SoC string contains
 * [ModelInfo.requiredSocMarker]. Always true for non-NPU models. The check
 * is intentionally case-insensitive substring — manufacturers prefix the
 * marker inconsistently ("SM8750", "Qualcomm SM8750", …).
 *
 * `Build.SOC_MODEL` is only populated on API 31+; on older devices the
 * check returns false for NPU-gated models, which is the right default
 * (NPU support is API 31+ anyway).
 */
fun ModelInfo.matchesCurrentSoc(): Boolean {
    val marker = requiredSocMarker?.lowercase() ?: return true
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
    val soc = Build.SOC_MODEL?.lowercase() ?: return false
    return soc.contains(marker)
}

private const val NPU_REPO = "https://huggingface.co/litert-community/Gemma3-1B-IT/resolve/main"

/**
 * Built-in model catalog. Custom URLs from Settings are merged with this list
 * at render time.
 */
val AVAILABLE_MODELS: List<ModelInfo> = listOf(
    ModelInfo(
        id = "gemma-4-e2b",
        name = "Gemma 4 E2B IT",
        description = "Instruction tuned, multimodal-ready Gemma 4 in LiteRT-LM format (CPU / GPU). ~2.6 GB. Fastest of the two.",
        url = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
        filename = "gemma-4-e2b.litertlm",
        // SHA-256 verified locally against the actual downloaded artifact;
        // matches HF's xet-backed `x-linked-etag` header.
        sha256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c"
    ),
    ModelInfo(
        id = "gemma-4-e4b",
        name = "Gemma 4 E4B IT",
        description = "Larger Gemma 4 — more accurate, slower. LiteRT-LM format, ~4 GB.",
        url = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm",
        filename = "gemma-4-e4b.litertlm",
        // SHA-256 sourced from HF's `x-linked-etag` (same pattern as E2B,
        // empirically confirmed to be SHA-256 for these xet-backed files).
        sha256 = "0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0"
    ),

    /* ---- Gemma 3 1B NPU variants ----
     * Source: huggingface.co/litert-community/Gemma3-1B-IT, hosted as the
     * canonical NPU-compiled .litertlm files in Google's LiteRT-LM NPU guide
     * (ai.google.dev/edge/litert/next/litert_lm_npu). One file per target SoC
     * — picking the wrong one results in a delegate-init failure at runtime.
     *
     * These are smaller (~690 MB Qualcomm, ~1 GB MediaTek) than the CPU-side
     * Gemma 4 entries above because NPU paths quantize aggressively for the
     * accelerator and bake in a fixed KV budget (`ekv1280`). They are only
     * useful when the device has the vendor delegate runtime installed
     * (QAIRT for Snapdragon, NeuroPilot for Dimensity); on a stock build the
     * Catalog still lists them but they won't init.
     *
     * SHA-256 left null intentionally — the HF repo doesn't expose stable
     * checksums for these xet-backed files and we'd have to download all
     * seven to lock them in. Set per-entry once verified on real hardware.
     */
    ModelInfo(
        id = "gemma3-1b-it-npu-sm8550",
        name = "Gemma 3 1B IT · NPU (SM8550)",
        description = "NPU-compiled Gemma 3 1B for Snapdragon 8 Gen 2. ~690 MB. Requires QAIRT runtime + ADSP_LIBRARY_PATH.",
        url = "$NPU_REPO/Gemma3-1B-IT_q4_ekv1280_sm8550.litertlm",
        filename = "gemma3-1b-it-npu-sm8550.litertlm",
        requiredSocMarker = "sm8550",
    ),
    ModelInfo(
        id = "gemma3-1b-it-npu-sm8650",
        name = "Gemma 3 1B IT · NPU (SM8650)",
        description = "NPU-compiled Gemma 3 1B for Snapdragon 8 Gen 3. ~690 MB. Requires QAIRT runtime + ADSP_LIBRARY_PATH.",
        url = "$NPU_REPO/Gemma3-1B-IT_q4_ekv1280_sm8650.litertlm",
        filename = "gemma3-1b-it-npu-sm8650.litertlm",
        requiredSocMarker = "sm8650",
    ),
    ModelInfo(
        id = "gemma3-1b-it-npu-sm8750",
        name = "Gemma 3 1B IT · NPU (SM8750)",
        description = "NPU-compiled Gemma 3 1B for Snapdragon 8 Elite (S25). ~689 MB. Requires QAIRT runtime + ADSP_LIBRARY_PATH.",
        url = "$NPU_REPO/Gemma3-1B-IT_q4_ekv1280_sm8750.litertlm",
        filename = "gemma3-1b-it-npu-sm8750.litertlm",
        requiredSocMarker = "sm8750",
    ),
    ModelInfo(
        id = "gemma3-1b-it-npu-sm8850",
        name = "Gemma 3 1B IT · NPU (SM8850)",
        description = "NPU-compiled Gemma 3 1B for Snapdragon 8 Elite Gen 5. ~694 MB. Requires QAIRT runtime + ADSP_LIBRARY_PATH.",
        url = "$NPU_REPO/Gemma3-1B-IT_q4_ekv1280_sm8850.litertlm",
        filename = "gemma3-1b-it-npu-sm8850.litertlm",
        requiredSocMarker = "sm8850",
    ),
    ModelInfo(
        id = "gemma3-1b-it-npu-mt6989",
        name = "Gemma 3 1B IT · NPU (MT6989)",
        description = "NPU-compiled Gemma 3 1B for MediaTek Dimensity 9300. ~1.03 GB. Requires NeuroPilot runtime.",
        url = "$NPU_REPO/Gemma3-1B-IT_q4_ekv1280_mt6989.litertlm",
        filename = "gemma3-1b-it-npu-mt6989.litertlm",
        requiredSocMarker = "mt6989",
    ),
    ModelInfo(
        id = "gemma3-1b-it-npu-mt6991",
        name = "Gemma 3 1B IT · NPU (MT6991)",
        description = "NPU-compiled Gemma 3 1B for MediaTek Dimensity 9400. ~1.03 GB. Requires NeuroPilot runtime.",
        url = "$NPU_REPO/Gemma3-1B-IT_q4_ekv1280_mt6991.litertlm",
        filename = "gemma3-1b-it-npu-mt6991.litertlm",
        requiredSocMarker = "mt6991",
    ),
    ModelInfo(
        id = "gemma3-1b-it-npu-mt6993",
        name = "Gemma 3 1B IT · NPU (MT6993)",
        description = "NPU-compiled Gemma 3 1B for MediaTek Dimensity 9500. ~1.02 GB. Requires NeuroPilot runtime.",
        url = "$NPU_REPO/Gemma3-1B-IT_q4_ekv1280_mt6993.litertlm",
        filename = "gemma3-1b-it-npu-mt6993.litertlm",
        requiredSocMarker = "mt6993",
    ),
    ModelInfo(
        // Tensor G5 (Pixel 10) variant. Uses int8 quantization with KV
        // budget 1280 — the only one Google has published for the Tensor
        // family so far. Runs via `Backend.NPU(nativeLibraryDir)` against
        // libLiteRtDispatch_GoogleTensor.so already bundled in jniLibs.
        // No Gemma 4 Tensor variant exists upstream — see
        // tooling/tensor-aot/ISSUE-google-ai-edge-litert.md.
        id = "gemma3-1b-it-npu-tensor-g5",
        name = "Gemma 3 1B IT · NPU (Tensor G5)",
        description = "NPU-compiled Gemma 3 1B for Google Tensor G5 (Pixel 10). ~1.68 GB (q8 quantization — larger than the q4 Qualcomm/MediaTek variants). Runs on the Tensor TPU via bundled dispatch lib — no extra runtime install.",
        url = "$NPU_REPO/Gemma3-1B-IT_q8_ekv1280_Google_Tensor_G5.litertlm",
        filename = "gemma3-1b-it-npu-tensor-g5.litertlm",
        // Pixel 10's Tensor G5 reports SoC codename "LAGUNA" via
        // ro.soc.model — not "Tensor G5". Verified on a Frankel device.
        requiredSocMarker = "laguna",
    ),
    ModelInfo(
        // Virtual entry — no .litertlm on disk. Served by ML Kit GenAI
        // Prompt API (com.google.mlkit:genai-prompt) against the AICore
        // system service. Chat handler routes this `id` to the AICore
        // bypass path. Real availability is gated by the device shipping
        // a beta2-compatible AICore + having Gemini Nano provisioned;
        // when that's not the case the chat call returns a structured
        // error and the server stays alive. See aicore/AICoreEngine.kt.
        id = com.localllm.app.aicore.AICoreEngine.MODEL_ID,
        name = "Gemini Nano · AICore",
        description = "System-provided Gemini Nano via Google AICore. No download — Android manages the model. Requires Pixel 8+ with a compatible AICore build. NOTE: AICore enforces foreground-only usage — the chat tab must be on-screen when an HTTP client calls `gemini-nano-aicore`, otherwise the SDK returns ErrorCode 30 (background usage blocked).",
        url = "",
        filename = "",
        isVirtual = true,
    ),
)
