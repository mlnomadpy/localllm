package com.localllm.app.server.routes

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import com.localllm.app.RateLimiter
import com.localllm.app.RequestTracker
import com.localllm.app.Settings
import com.localllm.app.inference.EngineRegistry
import com.localllm.app.inference.litert.SessionManager
import com.localllm.app.server.installTestServer
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Real engines need LiteRT / AICore native code; we only exercise the route's
 * front-end validation here — rate-limit, body cap, prompt-char cap, malformed
 * JSON, engine-acquire failures. The streaming + tool-call paths are
 * inherently engine-dependent and live in instrumentation tests instead.
 */
@RunWith(RobolectricTestRunner::class)
class ChatRouteTest {

    private val gson = Gson()
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val lastActivity = AtomicLong(0)
    private lateinit var serviceScope: CoroutineScope
    private lateinit var inferenceMutex: Mutex

    @Before fun setUp() = runTest {
        Settings.prefs(context).edit().clear().apply()
        RequestTracker.resetAll(); RequestTracker.clearHistory(); RequestTracker.resetStats()
        serviceScope = CoroutineScope(SupervisorJob())
        inferenceMutex = Mutex()
    }

    @After fun tearDown() {
        serviceScope.cancel()
    }

    private fun deps(): Triple<EngineRegistry, SessionManager, RateLimiter> {
        val registry = EngineRegistry(context)
        return Triple(registry, SessionManager(registry), RateLimiter(0.0, 10.0))
    }

    @Test
    fun `400 when body cannot be parsed as ChatRequest`() = testApplication {
        val (er, sm, rl) = deps()
        installTestServer {
            chatRoute(
                appContext = context, engineRegistry = er, sessionManager = sm,
                rateLimiter = rl, inferenceMutex = inferenceMutex,
                serviceScope = serviceScope, lastActivityAt = lastActivity,
                acquireWakeLock = { _, b -> b() },
            )
        }
        val resp = client.post("/v1/chat/completions") {
            contentType(ContentType.Application.Json); setBody("not json")
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }

    @Test
    fun `413 when prompt exceeds the per-request char cap`() = testApplication {
        Settings.setMaxPromptChars(context, 64)
        val (er, sm, rl) = deps()
        installTestServer {
            chatRoute(context, er, sm, rl, inferenceMutex, serviceScope, lastActivity) { _, b -> b() }
        }
        val huge = "x".repeat(2048)
        val resp = client.post("/v1/chat/completions") {
            contentType(ContentType.Application.Json)
            setBody("""{"model":"m","messages":[{"role":"user","content":"$huge"}]}""")
        }
        assertEquals(HttpStatusCode.PayloadTooLarge, resp.status)
    }

    @Test
    fun `429 when queue is full`() = testApplication {
        Settings.setMaxQueueDepth(context, 1)
        // Manually park one entry in the queue so the next request hits the cap.
        val parked = RequestTracker.tryEnqueue("m", false, 1, 10, maxDepth = 1, client = "ua")
        assertTrue("seed enqueue must succeed", parked != null)

        val (er, sm, rl) = deps()
        installTestServer {
            chatRoute(context, er, sm, rl, inferenceMutex, serviceScope, lastActivity) { _, b -> b() }
        }
        val resp = client.post("/v1/chat/completions") {
            contentType(ContentType.Application.Json)
            setBody("""{"model":"m","messages":[{"role":"user","content":"hi"}]}""")
        }
        assertEquals(HttpStatusCode.TooManyRequests, resp.status)
        assertEquals("5", resp.headers["Retry-After"])
    }

    @Test
    fun `429 when per-client rate-limit is exhausted`() = testApplication {
        Settings.setRateLimitPerSec(context, 1.0)
        Settings.setRateLimitBurst(context, 1.0)
        val (er, sm, rl) = deps()
        installTestServer {
            chatRoute(context, er, sm, rl, inferenceMutex, serviceScope, lastActivity) { _, b -> b() }
        }
        // First call consumes the only burst token (will then 5xx because there
        // is no real engine — we only care about the second call's 429).
        client.post("/v1/chat/completions") {
            contentType(ContentType.Application.Json)
            header("User-Agent", "ua-1")
            setBody("""{"model":"m","messages":[{"role":"user","content":"hi"}]}""")
        }
        val resp = client.post("/v1/chat/completions") {
            contentType(ContentType.Application.Json)
            header("User-Agent", "ua-1")
            setBody("""{"model":"m","messages":[{"role":"user","content":"hi"}]}""")
        }
        assertEquals(HttpStatusCode.TooManyRequests, resp.status)
        assertTrue("Retry-After header missing", resp.headers["Retry-After"] != null)
    }

    @Test
    fun `engine acquire failure for missing model file returns 503 envelope`() = testApplication {
        val (er, sm, rl) = deps()
        installTestServer {
            chatRoute(context, er, sm, rl, inferenceMutex, serviceScope, lastActivity) { _, b -> b() }
        }
        val resp = client.post("/v1/chat/completions") {
            contentType(ContentType.Application.Json)
            setBody("""{"model":"side-loaded-nonexistent","messages":[{"role":"user","content":"hi"}]}""")
        }
        assertEquals(HttpStatusCode.ServiceUnavailable, resp.status)
        val obj = gson.fromJson(resp.bodyAsText(), Map::class.java) as Map<*, *>
        val err = obj["error"] as Map<*, *>
        assertEquals("litert_engine_failed", err["type"])
        assertEquals("LITERT_INIT_FAILED", err["code"])
        val nextSteps = err["next_steps"] as List<*>
        assertTrue(nextSteps.isNotEmpty())
    }
}

private fun io.ktor.client.request.HttpRequestBuilder.header(name: String, value: String) {
    headers.append(name, value)
}
