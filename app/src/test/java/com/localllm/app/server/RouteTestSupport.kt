package com.localllm.app.server

import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.serialization.gson.gson
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.Routing
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder

/**
 * Mount Gson content-negotiation and a single Route extension into an
 * [ApplicationTestBuilder]. Used by every Ktor route test so we don't
 * repeat the install boilerplate.
 *
 * The returned [HttpClient] also has client-side content negotiation set up
 * so `body<MyDataClass>()` works in tests just like the prod code.
 */
fun ApplicationTestBuilder.installTestServer(routes: Routing.() -> Unit) {
    application {
        install(ContentNegotiation) { gson() }
        routing { routes() }
    }
}

/** Convenience extension to build an HttpClient inside a test that uses Gson. */
fun ApplicationTestBuilder.gsonClient(): HttpClient = createClient {
    install(ClientContentNegotiation) { gson() }
}

internal fun applyRoutes(app: Application, block: Routing.() -> Unit) {
    app.install(ContentNegotiation) { gson() }
    app.routing(block)
}
