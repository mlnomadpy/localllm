package com.localllm.app.server.routes

import com.localllm.app.inference.aicore.AICoreEngine
import com.localllm.app.server.ServerDeps
import com.localllm.app.server.auth.authorize
import io.ktor.server.request.queryString
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * `GET /v1/aicore/status` — surfaces the AICore (Gemini Nano) readiness
 * state. Useful for clients that want to wait for the system service to
 * download the model before sending a real chat request. The chat handler
 * still does its own pre-flight check; this endpoint just exposes that
 * check explicitly.
 *
 * `?probe=all` adds a per-config breakdown across (releaseStage × preference)
 * — `stable/fast`, `stable/full`, `preview/fast`, `preview/full`. Useful
 * for diagnosing devices where the default `preview/fast` is unavailable
 * but another combination might be provisioned.
 */
fun Route.aiCoreRoute(deps: ServerDeps) {
    get("/v1/aicore/status") {
        if (!authorize(call, deps.appContext)) return@get
        val (code, probeError) = try {
            AICoreEngine.checkStatusCode() to null
        } catch (e: Throwable) {
            -1 to (e.message ?: e.javaClass.simpleName)
        }
        val available = code == AICoreEngine.STATUS_AVAILABLE
        val base = linkedMapOf<String, Any?>(
            "model_id" to AICoreEngine.MODEL_ID,
            "status_code" to code,
            "status" to AICoreEngine.statusLabel(code),
            "available" to available,
            "soc_model" to (android.os.Build.SOC_MODEL ?: "unknown"),
            "device" to android.os.Build.DEVICE,
            "manufacturer" to android.os.Build.MANUFACTURER,
        )
        if (probeError != null) base["error"] = probeError
        val probeAll = call.request.queryParameters["probe"] == "all"
        if (probeAll) {
            base["configs"] = try {
                AICoreEngine.probeAllConfigs().mapValues { (_, v) ->
                    mapOf("code" to v, "label" to AICoreEngine.statusLabel(v))
                }
            } catch (e: Throwable) {
                mapOf("error" to (e.message ?: e.javaClass.simpleName))
            }
        }
        call.respond(base)
    }
}
