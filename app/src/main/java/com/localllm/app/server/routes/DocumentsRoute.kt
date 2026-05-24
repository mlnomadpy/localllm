package com.localllm.app.server.routes

import com.localllm.app.DocumentDeleteResponse
import com.localllm.app.DocumentListResponse
import com.localllm.app.DocumentRequest
import com.localllm.app.DocumentSummaryResponse
import com.localllm.app.ErrorDetails
import com.localllm.app.ErrorResponse
import com.localllm.app.LogManager
import com.localllm.app.SearchHit
import com.localllm.app.SearchRequest
import com.localllm.app.SearchResponse
import com.localllm.app.Settings
import com.localllm.app.TenantDeleteResponse
import com.localllm.app.TenantListResponse
import com.localllm.app.TenantSummaryResponse
import com.localllm.app.rag.DocumentChunk
import com.localllm.app.rag.Chunker
import com.localllm.app.server.ServerDeps
import com.localllm.app.server.auth.authorize
import com.localllm.app.server.respondWithTenant
import com.localllm.app.server.tenantFromCall
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post

/**
 * `POST/GET/DELETE /v1/documents*`, `/v1/tenants*`, `POST /v1/search` —
 * the RAG corpus surface. Tenant resolution comes from headers via
 * [tenantFromCall]; responses include the tenant id both in the body and
 * as an `X-Tenant-Id` header so clients can verify the routing they got.
 */
fun Route.documentsRoute(deps: ServerDeps) {
    post("/v1/documents") {
        if (!authorize(call, deps.appContext)) return@post
        deps.lastActivityAt.set(System.currentTimeMillis())
        val tenantId = tenantFromCall(call)

        val req = try {
            call.receive<DocumentRequest>()
        } catch (e: Exception) {
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse(ErrorDetails(
                    "Invalid request body: ${e.message ?: e.javaClass.simpleName}",
                    "invalid_request_error", 400,
                )),
            )
            return@post
        }
        if (req.id.isBlank() || req.text.isBlank() || req.model.isBlank()) {
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse(ErrorDetails(
                    "id, text, and model are required",
                    "invalid_request_error", 400,
                )),
            )
            return@post
        }

        val maxChars = Settings.maxPromptChars(deps.appContext)
        if (req.text.length > maxChars * 50) {
            call.respond(
                HttpStatusCode.PayloadTooLarge,
                ErrorResponse(ErrorDetails(
                    "Document text exceeds the per-upload size cap",
                    "invalid_request_error", 413,
                )),
            )
            return@post
        }

        val chunks = Chunker.chunk(req.text)
        if (chunks.isEmpty()) {
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse(ErrorDetails(
                    "Document text is empty after trimming",
                    "invalid_request_error", 400,
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
                    "Embedding model '${req.model}' not available: ${e.message ?: e.javaClass.simpleName}",
                    "invalid_request_error", 404,
                )),
            )
            return@post
        }

        try {
            val vectors = svc.embed(chunks)
            val metadataStr = req.metadata?.toString()
            val entities = chunks.mapIndexed { i, text ->
                DocumentChunk(
                    tenantId = tenantId,
                    documentId = req.id,
                    chunkIndex = i,
                    text = text,
                    metadata = metadataStr,
                    embeddingModel = req.model,
                    embedding = vectors[i].first,
                )
            }
            deps.documentStore().deleteDocument(tenantId, req.id)
            deps.documentStore().put(entities)
            call.respondWithTenant(
                tenantId,
                DocumentSummaryResponse(
                    documentId = req.id,
                    chunkCount = entities.size,
                    model = req.model,
                    tenantId = tenantId,
                ),
            )
        } catch (e: Throwable) {
            LogManager.e("DocumentsRoute", "Document ingest failed: ${e.message}", e)
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse(ErrorDetails(
                    "Document ingest failed: ${e.message ?: e.javaClass.simpleName}",
                    "api_error", 500,
                )),
            )
        }
    }

    get("/v1/documents") {
        if (!authorize(call, deps.appContext)) return@get
        val tenantId = tenantFromCall(call)
        val summaries = deps.documentStore().listDocuments(tenantId).map {
            DocumentSummaryResponse(
                documentId = it.documentId,
                chunkCount = it.chunkCount,
                model = it.embeddingModel,
                tenantId = tenantId,
            )
        }
        call.respondWithTenant(
            tenantId,
            DocumentListResponse(data = summaries, tenantId = tenantId),
        )
    }

    delete("/v1/documents/{id}") {
        if (!authorize(call, deps.appContext)) return@delete
        val tenantId = tenantFromCall(call)
        val id = call.parameters["id"]
        if (id.isNullOrBlank()) {
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse(ErrorDetails("id is required", "invalid_request_error", 400)),
            )
            return@delete
        }
        val n = deps.documentStore().deleteDocument(tenantId, id)
        call.respondWithTenant(
            tenantId,
            DocumentDeleteResponse(
                documentId = id,
                deleted = n > 0,
                chunksRemoved = n,
                tenantId = tenantId,
            ),
        )
    }

    /* ----- /v1/tenants (admin — global view, not tenant-scoped) ----- */

    get("/v1/tenants") {
        if (!authorize(call, deps.appContext)) return@get
        val summaries = deps.documentStore().listTenants().map {
            TenantSummaryResponse(
                tenantId = it.tenantId,
                documentCount = it.documentCount,
                chunkCount = it.chunkCount,
            )
        }
        call.respond(TenantListResponse(data = summaries))
    }

    delete("/v1/tenants/{tenantId}") {
        if (!authorize(call, deps.appContext)) return@delete
        val tenantId = call.parameters["tenantId"]?.trim()?.lowercase().orEmpty()
        if (tenantId.isBlank()) {
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse(ErrorDetails("tenantId is required", "invalid_request_error", 400)),
            )
            return@delete
        }
        val n = deps.documentStore().deleteTenant(tenantId)
        call.respond(TenantDeleteResponse(
            tenantId = tenantId,
            deleted = n > 0,
            chunksRemoved = n,
        ))
    }

    /* ----- /v1/search (kNN over the document store) ----- */

    post("/v1/search") {
        if (!authorize(call, deps.appContext)) return@post
        deps.lastActivityAt.set(System.currentTimeMillis())
        val tenantId = tenantFromCall(call)

        val req = try { call.receive<SearchRequest>() } catch (e: Exception) {
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse(ErrorDetails(
                    "Invalid request body: ${e.message ?: e.javaClass.simpleName}",
                    "invalid_request_error", 400,
                )),
            )
            return@post
        }
        if (req.query.isBlank() || req.model.isBlank()) {
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse(ErrorDetails("query and model are required", "invalid_request_error", 400)),
            )
            return@post
        }
        val k = (req.k ?: 5).coerceIn(1, 50)

        val svc = try {
            deps.embeddingRegistry.acquire(req.model)
        } catch (e: Exception) {
            call.respond(
                HttpStatusCode.NotFound,
                ErrorResponse(ErrorDetails(
                    "Embedding model '${req.model}' not available: ${e.message ?: e.javaClass.simpleName}",
                    "invalid_request_error", 404,
                )),
            )
            return@post
        }

        try {
            val queryVec = svc.embed(listOf(req.query)).first().first
            val hits = deps.documentStore().nearest(tenantId, queryVec, k, req.model).map { (chunk, distance) ->
                val cosine = 1f - distance
                val metaJson: com.google.gson.JsonElement? = chunk.metadata?.let {
                    runCatching { com.google.gson.JsonParser.parseString(it) }.getOrNull()
                }
                SearchHit(
                    documentId = chunk.documentId,
                    chunkIndex = chunk.chunkIndex,
                    text = chunk.text,
                    score = cosine,
                    metadata = metaJson,
                )
            }
            call.respondWithTenant(
                tenantId,
                SearchResponse(data = hits, model = req.model, tenantId = tenantId),
            )
        } catch (e: Throwable) {
            LogManager.e("DocumentsRoute", "Search failed: ${e.message}", e)
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse(ErrorDetails(
                    "Search failed: ${e.message ?: e.javaClass.simpleName}",
                    "api_error", 500,
                )),
            )
        }
    }
}
