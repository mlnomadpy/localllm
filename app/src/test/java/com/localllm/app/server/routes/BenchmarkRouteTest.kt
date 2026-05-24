package com.localllm.app.server.routes

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import com.localllm.app.Settings
import com.localllm.app.inference.aicore.AICoreBenchmark
import com.localllm.app.inference.aicore.AICoreBenchmarkCache
import com.localllm.app.server.installTestServer
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BenchmarkRouteTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val lastActivity = AtomicLong(0)

    @Before fun setUp() {
        Settings.prefs(context).edit().clear().apply()
        AICoreBenchmarkCache.latest = null
    }

    @Test
    fun `POST refuses when AICore is not AVAILABLE`() = testApplication {
        installTestServer { benchmarkRoute(context, lastActivity) }
        val resp = client.post("/v1/aicore/benchmark")
        // AICore probe fails on JVM → 503 with structured envelope.
        assertEquals(HttpStatusCode.ServiceUnavailable, resp.status)
    }

    @Test
    fun `GET with no cached result returns 404`() = testApplication {
        installTestServer { benchmarkRoute(context, lastActivity) }
        val resp = client.get("/v1/aicore/benchmark")
        assertEquals(HttpStatusCode.NotFound, resp.status)
    }

    @Test
    fun `GET with cached result serves it`() = testApplication {
        AICoreBenchmarkCache.latest = AICoreBenchmark.BenchmarkResult(
            runs = emptyList(),
            avgTtftMs = 1.0,
            avgTotalMs = 2.0,
            avgTokensPerSec = 3.0,
            totalTokensGenerated = 4,
            deviceModel = "robolectric",
            socModel = "x",
            aicoreStatus = "unavailable",
            timestamp = 1L,
        )
        installTestServer { benchmarkRoute(context, lastActivity) }
        val resp = client.get("/v1/aicore/benchmark")
        assertEquals(HttpStatusCode.OK, resp.status)
        val obj = Gson().fromJson(resp.bodyAsText(), Map::class.java) as Map<*, *>
        assertEquals(4.0, (obj["totalTokensGenerated"] as Number).toDouble(), 0.0)
    }
}
