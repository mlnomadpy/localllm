package com.localllm.app.server.routes

import com.localllm.app.inference.aicore.AICoreEngine
import com.localllm.app.server.ServerDeps
import com.localllm.app.server.auth.authorize
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * `GET /v1/aicore/status` — surfaces the AICore (Gemini Nano) readiness
 * state. Useful for clients that want to wait for the system service to
 * download the model before sending a real chat request. The chat handler
 * still does its own pre-flight check; this endpoint just exposes that
 * check explicitly.
 */
fun Route.aiCoreRoute(deps: ServerDeps) {
    get("/v1/aicore/status") {
        if (!authorize(call, deps.appContext)) return@get
        val code = try {
            AICoreEngine.checkStatusCode()
        } catch (e: Throwable) {
            -1
        }
        val available = code == AICoreEngine.STATUS_AVAILABLE
        call.respond(mapOf(
            "model_id" to AICoreEngine.MODEL_ID,
            "status_code" to code,
            "status" to AICoreEngine.statusLabel(code),
            "available" to available,
        ))
    }
}
