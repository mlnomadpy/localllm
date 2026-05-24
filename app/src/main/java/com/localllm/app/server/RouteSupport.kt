package com.localllm.app.server

import com.localllm.app.rag.resolveTenantFromHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respond

/** Resolve the RAG tenant for [call] — see `resolveTenantFromHeaders`. */
fun tenantFromCall(call: ApplicationCall): String =
    resolveTenantFromHeaders(
        clientId = call.request.headers["X-Client-Id"],
        userAgent = call.request.headers["User-Agent"],
    )

/** Attach `X-Tenant-Id` and emit a JSON body that includes `tenant_id`. */
suspend inline fun <reified T : Any> ApplicationCall.respondWithTenant(
    tenantId: String,
    body: T,
) {
    response.header("X-Tenant-Id", tenantId)
    respond(body)
}
