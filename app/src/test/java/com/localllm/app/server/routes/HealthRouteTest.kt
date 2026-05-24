package com.localllm.app.server.routes

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import com.localllm.app.Settings
import com.localllm.app.inference.EngineRegistry
import com.localllm.app.server.installTestServer
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HealthRouteTest {

    private val gson = Gson()
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before fun setUp() {
        Settings.prefs(context).edit().clear().apply()
    }

    @Test
    fun `health returns ok with service identity and AICore block`() = testApplication {
        installTestServer { healthRoute(EngineRegistry(context)) }
        val resp = client.get("/health")
        assertEquals(HttpStatusCode.OK, resp.status)
        val obj = gson.fromJson(resp.bodyAsText(), Map::class.java) as Map<*, *>
        assertEquals("ok", obj["status"])
        assertEquals("localllm-android", obj["service"])
        assertTrue("expected aicore block", obj["aicore"] is Map<*, *>)
    }

    @Test
    fun `health engines list is empty on a fresh registry`() = testApplication {
        installTestServer { healthRoute(EngineRegistry(context)) }
        val resp = client.get("/health")
        val obj = gson.fromJson(resp.bodyAsText(), Map::class.java) as Map<*, *>
        val n = (obj["engines_loaded"] as Number).toInt()
        assertEquals(0, n)
        val engines = obj["engines"] as List<*>
        assertTrue(engines.isEmpty())
    }

    @Test
    fun `warm endpoint not mounted when appContext is absent`() = testApplication {
        installTestServer { healthRoute(EngineRegistry(context)) }
        val resp = client.post("/health/warm")
        // No route registered for POST /health/warm → 405 or 404, never 200.
        assertTrue(resp.status.value in 400..499)
    }

    @Test
    fun `warm endpoint returns error envelope for missing model file`() = testApplication {
        val registry = EngineRegistry(context)
        installTestServer {
            healthRoute(registry, appContext = context, inferenceMutex = Mutex())
        }
        // Pick a model id the catalog defaults to LITERT_CPU, with no .litertlm on disk.
        val resp = client.post("/health/warm?model=does-not-exist")
        // ServiceUnavailable: engine acquire throws "Model file not found".
        assertEquals(HttpStatusCode.ServiceUnavailable, resp.status)
        val obj = gson.fromJson(resp.bodyAsText(), Map::class.java) as Map<*, *>
        assertEquals("error", obj["status"])
        assertEquals("does-not-exist", obj["model"])
        assertTrue("expected ms field", obj["ms"] is Number)
    }
}
