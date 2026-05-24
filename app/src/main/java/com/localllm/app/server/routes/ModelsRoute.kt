package com.localllm.app.server.routes

import android.content.Context
import com.localllm.app.ModelData
import com.localllm.app.ModelListResponse
import com.localllm.app.inference.EmbeddingRegistry
import com.localllm.app.inference.aicore.AICoreEngine
import com.localllm.app.server.auth.authorize
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * `GET /v1/models` — OpenAI-shaped model index. Combines:
 *   - `.litertlm` files on disk (chat-capable)
 *   - `.onnx` files with matching vocab.txt (embedding-capable)
 *   - the virtual AICore entry (listed unconditionally; the chat handler
 *     surfaces a clean error if AICore is unavailable on the device)
 */
fun Route.modelsRoute(
    appContext: Context,
    embeddingRegistry: EmbeddingRegistry,
) {
    get("/v1/models") {
        if (!authorize(call, appContext)) return@get
        val dir = appContext.getExternalFilesDir(null)
        val all = dir?.listFiles() ?: emptyArray()
        val llmModels = all.filter { it.name.endsWith(".litertlm") }.map { file ->
            val modelId = file.name.removeSuffix(".litertlm")
            ModelData(id = modelId, created = file.lastModified() / 1000)
        }
        val embModels = all.filter { it.name.endsWith(".onnx") }
            .mapNotNull { file ->
                val modelId = file.name.removeSuffix(".onnx")
                if (embeddingRegistry.resolveVocabFor(modelId) == null) null
                else ModelData(id = modelId, created = file.lastModified() / 1000)
            }
        val aicoreModel = ModelData(
            id = AICoreEngine.MODEL_ID,
            created = System.currentTimeMillis() / 1000,
            ownedBy = "google-aicore",
        )
        call.respond(ModelListResponse(data = llmModels + embModels + aicoreModel))
    }
}
