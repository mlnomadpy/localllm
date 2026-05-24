package com.localllm.app.server.routes

import com.localllm.app.RequestTracker
import com.localllm.app.inference.EngineRegistry
import io.ktor.http.ContentType
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * `GET /metrics` — Prometheus exposition. Unauthenticated by design so a
 * monitoring agent on the LAN doesn't need to know the API key (same posture
 * as `/health`).
 *
 * Surface: cumulative counters from [RequestTracker.stats], live gauges from
 * the queue / current-request flows, per-client breakdown from
 * [RequestTracker.clientSummaries], and one gauge per cached LiteRT engine.
 *
 * Labels are kept lowercase / sanitised so a malicious client can't break
 * the exposition format by sending an exotic User-Agent.
 */
fun Route.metricsRoute(engineRegistry: EngineRegistry) {
    get("/metrics") {
        val sb = StringBuilder(2_048)
        val stats = RequestTracker.stats.value
        val queue = RequestTracker.queue.value
        val current = RequestTracker.current.value
        val clients = RequestTracker.clientSummaries(maxClients = 32)

        fun help(name: String, type: String, helpText: String) {
            sb.append("# HELP ").append(name).append(' ').append(helpText).append('\n')
            sb.append("# TYPE ").append(name).append(' ').append(type).append('\n')
        }

        help("localllm_requests_total", "counter", "Total /v1/chat/completions requests accepted.")
        sb.append("localllm_requests_total ").append(stats.totalRequests).append('\n')

        help("localllm_requests_completed_total", "counter", "Requests that finished successfully.")
        sb.append("localllm_requests_completed_total ").append(stats.totalCompleted).append('\n')

        help("localllm_requests_errored_total", "counter", "Requests that errored (server-side failure).")
        sb.append("localllm_requests_errored_total ").append(stats.totalErrors).append('\n')

        help("localllm_requests_cancelled_total", "counter", "Requests cancelled by client or service shutdown.")
        sb.append("localllm_requests_cancelled_total ").append(stats.totalCancelled).append('\n')

        help("localllm_stream_chunks_total", "counter", "Total streamed delta chunks emitted across all requests.")
        sb.append("localllm_stream_chunks_total ").append(stats.totalChunks).append('\n')

        help("localllm_inference_seconds_total", "counter", "Cumulative inference wall time across completed requests.")
        sb.append("localllm_inference_seconds_total ").append(stats.totalInferenceMs / 1000.0).append('\n')

        help("localllm_inference_seconds_avg", "gauge", "Average inference duration over completed requests.")
        sb.append("localllm_inference_seconds_avg ").append(stats.avgLatencyMs / 1000.0).append('\n')

        help("localllm_chunks_per_second_avg", "gauge", "Average chunks per second over completed requests.")
        sb.append("localllm_chunks_per_second_avg ").append(stats.avgChunksPerSec).append('\n')

        help("localllm_queue_depth", "gauge", "Requests currently waiting for the inference mutex.")
        sb.append("localllm_queue_depth ").append(queue.size).append('\n')

        help("localllm_inflight", "gauge", "Request currently executing (0 or 1).")
        sb.append("localllm_inflight ").append(if (current != null) 1 else 0).append('\n')

        help("localllm_engines_loaded", "gauge", "Number of cached LiteRT engines (excludes AICore).")
        sb.append("localllm_engines_loaded ").append(engineRegistry.engineCount()).append('\n')

        help("localllm_engine_loaded", "gauge", "1 if the named engine is currently cached.")
        for (info in engineRegistry.snapshot()) {
            sb.append("localllm_engine_loaded{key=\"")
                .append(escape(info.cacheKey))
                .append("\",backend=\"")
                .append(escape(info.backend))
                .append("\"} 1\n")
        }

        help("localllm_client_requests_total", "counter", "Per-client request count (completed + queued + in-flight).")
        help("localllm_client_inference_seconds_avg", "gauge", "Per-client average inference seconds (completed only).")
        for (c in clients) {
            val cid = escape(c.client)
            val total = c.completed + c.queued.toLong() + c.inFlight.toLong()
            sb.append("localllm_client_requests_total{client=\"").append(cid).append("\"} ").append(total).append('\n')
            sb.append("localllm_client_inference_seconds_avg{client=\"").append(cid).append("\"} ")
                .append(c.avgInferenceMs / 1000.0).append('\n')
        }

        call.respondText(sb.toString(), contentType = ContentType.Text.Plain)
    }
}

private fun escape(s: String): String {
    if (s.isEmpty()) return s
    var needs = false
    for (i in s.indices) {
        val c = s[i]
        if (c == '\\' || c == '"' || c == '\n') { needs = true; break }
    }
    if (!needs) return s
    val out = StringBuilder(s.length + 8)
    for (i in s.indices) {
        when (val c = s[i]) {
            '\\' -> out.append("\\\\")
            '"' -> out.append("\\\"")
            '\n' -> out.append("\\n")
            else -> out.append(c)
        }
    }
    return out.toString()
}
