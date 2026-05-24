package com.localllm.app.server.routes

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.localllm.app.RequestTracker
import com.localllm.app.inference.EngineRegistry
import com.localllm.app.server.installTestServer
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MetricsRouteTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var registry: EngineRegistry

    @Before fun setUp() = runTest {
        RequestTracker.resetAll(); RequestTracker.clearHistory(); RequestTracker.resetStats()
        registry = EngineRegistry(context)
    }

    @After fun tearDown() = runTest {
        RequestTracker.resetAll(); RequestTracker.clearHistory(); RequestTracker.resetStats()
    }

    @Test
    fun `metrics endpoint is unauthenticated and returns text plain`() = testApplication {
        installTestServer { metricsRoute(registry) }
        val resp = client.get("/metrics")
        assertEquals(HttpStatusCode.OK, resp.status)
        val ct = resp.headers["Content-Type"].orEmpty()
        assertTrue("expected text/plain, got '$ct'", ct.startsWith("text/plain"))
    }

    @Test
    fun `body contains HELP and TYPE for every metric family`() = testApplication {
        installTestServer { metricsRoute(registry) }
        val body = client.get("/metrics").bodyAsText()
        listOf(
            "localllm_requests_total",
            "localllm_requests_completed_total",
            "localllm_requests_errored_total",
            "localllm_requests_cancelled_total",
            "localllm_stream_chunks_total",
            "localllm_inference_seconds_total",
            "localllm_inference_seconds_avg",
            "localllm_chunks_per_second_avg",
            "localllm_queue_depth",
            "localllm_inflight",
            "localllm_engines_loaded",
        ).forEach { metric ->
            assertTrue(
                "missing # HELP for $metric",
                body.contains("# HELP $metric "),
            )
            assertTrue(
                "missing # TYPE for $metric",
                body.contains("# TYPE $metric "),
            )
        }
    }

    @Test
    fun `counters reflect RequestTracker state`() = testApplication {
        installTestServer { metricsRoute(registry) }
        // Drive one completed and one errored request through the tracker.
        val done = RequestTracker.enqueue("m", false, 1, 100)
        RequestTracker.markStarted(done.id); RequestTracker.markCompleted(done.id)
        val err = RequestTracker.enqueue("m", false, 1, 100)
        RequestTracker.markStarted(err.id); RequestTracker.markCompleted(err.id, error = "boom")

        val body = client.get("/metrics").bodyAsText()
        assertTrue(body.contains("localllm_requests_total 2"))
        assertTrue(body.contains("localllm_requests_completed_total 1"))
        assertTrue(body.contains("localllm_requests_errored_total 1"))
    }

    @Test
    fun `client labels escape special characters`() = testApplication {
        installTestServer { metricsRoute(registry) }
        // Push an entry whose client id contains a double quote — the
        // exposition format must escape it so the line still parses.
        val entry = RequestTracker.tryEnqueue(
            model = "m",
            stream = false,
            messageCount = 1,
            promptChars = 10,
            maxDepth = 8,
            client = "evil\"client",
        )!!
        RequestTracker.markStarted(entry.id); RequestTracker.markCompleted(entry.id)

        val body = client.get("/metrics").bodyAsText()
        // The body must not contain the raw unescaped " inside the label.
        // It must contain the escaped \" sequence.
        assertTrue(
            "expected escaped quote in client label",
            body.contains("client=\"evil\\\"client\""),
        )
    }
}
