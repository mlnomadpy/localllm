package com.localllm.app.server.routes

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import com.localllm.app.Settings
import com.localllm.app.embedding.EmbeddingService
import com.localllm.app.inference.EmbeddingRegistry
import com.localllm.app.server.installTestServer
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.stub
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class EmbeddingsRouteTest {

    private val gson = Gson()
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val lastActivity = AtomicLong(0)

    @Before fun setUp() {
        Settings.prefs(context).edit().clear().apply()
    }

    private fun fakeRegistryReturning(vec: FloatArray, tokens: Int): EmbeddingRegistry {
        val service: EmbeddingService = mock {
            onBlocking { embed(any()) } doReturn listOf(vec to tokens)
        }
        return mock<EmbeddingRegistry> {
            on { acquire(any()) } doReturn service
        }
    }

    @Test
    fun `400 when body is malformed JSON`() = testApplication {
        installTestServer { embeddingsRoute(context, EmbeddingRegistry(context), lastActivity) }
        val resp = client.post("/v1/embeddings") {
            contentType(ContentType.Application.Json); setBody("not json")
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }

    @Test
    fun `400 when encoding_format is base64`() = testApplication {
        val registry = fakeRegistryReturning(floatArrayOf(0.1f, 0.2f), tokens = 3)
        installTestServer { embeddingsRoute(context, registry, lastActivity) }
        val resp = client.post("/v1/embeddings") {
            contentType(ContentType.Application.Json)
            setBody("""{"input":"hi","model":"m","encoding_format":"base64"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }

    @Test
    fun `413 when total input length exceeds prompt-char cap`() = testApplication {
        Settings.setMaxPromptChars(context, 1024)
        val registry = fakeRegistryReturning(floatArrayOf(0.1f), tokens = 1)
        installTestServer { embeddingsRoute(context, registry, lastActivity) }
        val huge = "x".repeat(2048)
        val resp = client.post("/v1/embeddings") {
            contentType(ContentType.Application.Json)
            setBody("""{"input":"$huge","model":"m"}""")
        }
        assertEquals(HttpStatusCode.PayloadTooLarge, resp.status)
    }

    @Test
    fun `404 when EmbeddingRegistry rejects the model`() = testApplication {
        val registry: EmbeddingRegistry = mock {
            on { acquire(any()) } doThrow IllegalStateException("vocab missing")
        }
        installTestServer { embeddingsRoute(context, registry, lastActivity) }
        val resp = client.post("/v1/embeddings") {
            contentType(ContentType.Application.Json)
            setBody("""{"input":"hi","model":"unknown"}""")
        }
        assertEquals(HttpStatusCode.NotFound, resp.status)
    }

    @Test
    fun `200 returns OpenAI-shaped response on a single string input`() = testApplication {
        val registry = fakeRegistryReturning(floatArrayOf(0.1f, 0.2f, 0.3f), tokens = 4)
        installTestServer { embeddingsRoute(context, registry, lastActivity) }
        val resp = client.post("/v1/embeddings") {
            contentType(ContentType.Application.Json)
            setBody("""{"input":"hello","model":"bge-small-en-v1.5"}""")
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        val obj = gson.fromJson(resp.bodyAsText(), Map::class.java) as Map<*, *>
        assertEquals("bge-small-en-v1.5", obj["model"])
        val data = obj["data"] as List<*>
        assertEquals(1, data.size)
        val usage = obj["usage"] as Map<*, *>
        assertEquals(4.0, (usage["prompt_tokens"] as Number).toDouble(), 0.0)
    }

    @Test
    fun `400 when input array entries are not strings`() = testApplication {
        val registry = fakeRegistryReturning(floatArrayOf(0.1f), tokens = 1)
        installTestServer { embeddingsRoute(context, registry, lastActivity) }
        val resp = client.post("/v1/embeddings") {
            contentType(ContentType.Application.Json)
            setBody("""{"input":[1,2,3],"model":"m"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
        assertTrue(resp.bodyAsText().contains("strings"))
    }
}
