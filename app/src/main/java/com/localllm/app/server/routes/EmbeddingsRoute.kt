package com.localllm.app.server.routes

import com.localllm.app.EmbeddingData
import com.localllm.app.EmbeddingRequest
import com.localllm.app.EmbeddingResponse
import com.localllm.app.EmbeddingUsage
import com.localllm.app.ErrorDetails
import com.localllm.app.ErrorResponse
import com.localllm.app.LogManager
import com.localllm.app.Settings
import com.localllm.app.inputStrings
import com.localllm.app.server.ServerDeps
import com.localllm.app.server.auth.authorize
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post

/**
 * `POST /v1/embeddings` — OpenAI-compatible. Uses ONNX models via the
 * [com.localllm.app.embedding.EmbeddingService] cached in
 * [com.localllm.app.inference.EmbeddingRegistry]. The same prompt-char cap
 * applies as for chat: a massive doc POSTed here gets the same 413 it'd get
 * from chat.
 */
fun Route.embeddingsRoute(deps: ServerDeps) {
    post("/v1/embeddings") {
        if (!authorize(call, deps.appContext)) return@post
        deps.lastActivityAt.set(System.currentTimeMillis())

        val req = try {
            call.receive<EmbeddingRequest>()
        } catch (e: Exception) {
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse(ErrorDetails(
                    message = "Invalid request body: ${e.message ?: e.javaClass.simpleName}",
                    type = "invalid_request_error",
                    code = 400,
                )),
            )
            return@post
        }

        if (req.encodingFormat != null && req.encodingFormat != "float") {
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse(ErrorDetails(
                    message = "encoding_format='${req.encodingFormat}' is not supported (only 'float')",
                    type = "invalid_request_error",
                    code = 400,
                )),
            )
            return@post
        }

        val texts = try { req.inputStrings() } catch (e: IllegalArgumentException) {
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse(ErrorDetails(
                    message = e.message ?: "invalid input",
                    type = "invalid_request_error",
                    code = 400,
                )),
            )
            return@post
        }

        val maxChars = Settings.maxPromptChars(deps.appContext)
        val totalChars = texts.sumOf { it.length }
        if (totalChars > maxChars) {
            call.respond(
                HttpStatusCode.PayloadTooLarge,
                ErrorResponse(ErrorDetails(
                    message = "Total input length $totalChars exceeds cap of $maxChars chars",
                    type = "invalid_request_error",
                    code = 413,
                )),
            )
            return@post
        }

        val svc = try {
            deps.embeddingRegistry.acquire(req.model)
        } catch (e: Exception) {
            call.respond(
                HttpStatusCode.NotFound,
                ErrorResponse(ErrorDetails(
                    message = "Embedding model '${req.model}' not available: ${e.message ?: e.javaClass.simpleName}",
                    type = "invalid_request_error",
                    code = 404,
                )),
            )
            return@post
        }

        try {
            val results = svc.embed(texts)
            val data = results.mapIndexed { i, (vec, _) ->
                EmbeddingData(embedding = vec, index = i)
            }
            val tokens = results.sumOf { it.second }
            call.respond(EmbeddingResponse(
                data = data,
                model = req.model,
                usage = EmbeddingUsage(promptTokens = tokens, totalTokens = tokens),
            ))
        } catch (e: Throwable) {
            LogManager.e("EmbeddingsRoute", "Embedding inference failed: ${e.message}", e)
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse(ErrorDetails(
                    message = "Embedding inference failed: ${e.message ?: e.javaClass.simpleName}",
                    type = "api_error",
                    code = 500,
                )),
            )
        }
    }
}
