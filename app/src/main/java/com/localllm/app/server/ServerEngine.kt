package com.localllm.app.server

import com.localllm.app.Settings
import com.localllm.app.server.routes.aiCoreRoute
import com.localllm.app.server.routes.benchmarkRoute
import com.localllm.app.server.routes.chatRoute
import com.localllm.app.server.routes.documentsRoute
import com.localllm.app.server.routes.embeddingsRoute
import com.localllm.app.server.routes.healthRoute
import com.localllm.app.server.routes.modelsRoute
import io.ktor.serialization.gson.gson
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.routing.routing
import io.ktor.server.application.install

/**
 * Builds a Ktor [EmbeddedServer] configured with the OpenAI-compatible API
 * surface. Kept separate from [com.localllm.app.LLMServerService] so the
 * Service shrinks to lifecycle + dependencies and the route layout lives
 * in one focused file.
 *
 * CORS is opt-in. Native HTTP clients don't need CORS headers, so the safe
 * default is "no CORS plugin" — that way a random web page can't drive the
 * API from a user's browser.
 */
object ServerEngine {

    fun build(
        deps: ServerDeps,
        port: Int,
        host: String,
    ): EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration> =
        embeddedServer(Netty, port = port, host = host) {
            install(ContentNegotiation) { gson() }
            if (Settings.allowCors(deps.appContext)) {
                install(CORS) {
                    anyHost()
                    allowMethod(io.ktor.http.HttpMethod.Post)
                    allowMethod(io.ktor.http.HttpMethod.Get)
                    allowHeader(io.ktor.http.HttpHeaders.ContentType)
                    allowHeader(io.ktor.http.HttpHeaders.Authorization)
                }
            }
            routing {
                healthRoute(deps.engineRegistry)
                modelsRoute(deps.appContext, deps.embeddingRegistry)
                embeddingsRoute(deps.appContext, deps.embeddingRegistry, deps.lastActivityAt)
                documentsRoute(
                    deps.appContext,
                    deps.embeddingRegistry,
                    deps.documentStore,
                    deps.lastActivityAt,
                )
                aiCoreRoute(deps.appContext)
                benchmarkRoute(deps.appContext, deps.lastActivityAt)
                chatRoute(deps)
            }
        }
}
