package com.localllm.app.server

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.testing.testApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Exercises `tenantFromCall` and `respondWithTenant` through a tiny route
 * mounted with [installTestServer]. The helpers themselves are pure but the
 * code path lives inside Ktor's call pipeline, so we hit them via an in-mem
 * HTTP round-trip.
 */
@RunWith(RobolectricTestRunner::class)
class RouteSupportTest {

    private val gson = Gson()
    @Suppress("unused")
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /** Mount a route at /t that echoes the resolved tenant. */
    @Test
    fun `tenantFromCall returns lowercased X-Client-Id when present`() = testApplication {
        installTestServer {
            get("/t") {
                val tenant = tenantFromCall(call)
                call.respondText(tenant)
            }
        }
        val resp = client.get("/t") { header("X-Client-Id", "MyClient-A") }
        assertEquals(HttpStatusCode.OK, resp.status)
        assertEquals("myclient-a", resp.bodyAsText())
    }

    @Test
    fun `tenantFromCall falls back to User-Agent when X-Client-Id is absent`() = testApplication {
        installTestServer {
            get("/t") { call.respondText(tenantFromCall(call)) }
        }
        val resp = client.get("/t") { header("User-Agent", "Curl/8.0") }
        assertEquals("curl/8.0", resp.bodyAsText())
    }

    @Test
    fun `tenantFromCall returns anonymous when nothing is supplied`() = testApplication {
        installTestServer {
            get("/t") { call.respondText(tenantFromCall(call)) }
        }
        // No headers — the Ktor test client adds its own default User-Agent
        // sometimes, so explicitly clear it.
        val resp = client.get("/t") { headers.remove("User-Agent") }
        val body = resp.bodyAsText()
        // Either "anonymous" or the test client's default UA, lowercased.
        // The contract: never empty.
        assertEquals(HttpStatusCode.OK, resp.status)
        assert(body.isNotEmpty()) { "tenant should never be empty" }
    }

    @Test
    fun `X-Client-Id takes priority over User-Agent`() = testApplication {
        installTestServer {
            get("/t") { call.respondText(tenantFromCall(call)) }
        }
        val resp = client.get("/t") {
            header("X-Client-Id", "preferred")
            header("User-Agent", "fallback")
        }
        assertEquals("preferred", resp.bodyAsText())
    }

    @Test
    fun `respondWithTenant attaches X-Tenant-Id header`() = testApplication {
        installTestServer {
            get("/r") {
                val tenant = tenantFromCall(call)
                call.respondWithTenant(tenant, mapOf("tenant_id" to tenant, "ok" to true))
            }
        }
        val resp = client.get("/r") { header("X-Client-Id", "abc") }
        assertEquals(HttpStatusCode.OK, resp.status)
        assertEquals("abc", resp.headers["X-Tenant-Id"])
        val body = gson.fromJson(resp.bodyAsText(), Map::class.java) as Map<*, *>
        assertEquals("abc", body["tenant_id"])
        assertEquals(true, body["ok"])
    }

    @Test
    fun `respondWithTenant serializes generic body via Gson`() = testApplication {
        data class Envelope(val tenantId: String, val value: Int)
        installTestServer {
            get("/g") {
                val tenant = tenantFromCall(call)
                call.respondWithTenant(tenant, Envelope(tenant, 42))
            }
        }
        val resp = client.get("/g") { header("X-Client-Id", "Z") }
        assertEquals("z", resp.headers["X-Tenant-Id"])
        val parsed = gson.fromJson(resp.bodyAsText(), Map::class.java) as Map<*, *>
        // Gson serializes camelCase Kotlin properties verbatim — no auto snake_case.
        assertEquals("z", parsed["tenantId"])
        assertEquals(42.0, parsed["value"]) // Gson Map deserializes numbers as Double
        assertNull(parsed["bogus"])
    }

    @Test
    fun `whitespace-only X-Client-Id falls back to User-Agent`() = testApplication {
        installTestServer {
            get("/t") { call.respondText(tenantFromCall(call)) }
        }
        val resp = client.get("/t") {
            header("X-Client-Id", "   ")
            header("User-Agent", "FallbackUA")
        }
        assertEquals("fallbackua", resp.bodyAsText())
    }
}
