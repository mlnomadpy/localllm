package com.localllm.app.server.routes

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import com.localllm.app.Settings
import com.localllm.app.embedding.EmbeddingService
import com.localllm.app.inference.EmbeddingRegistry
import com.localllm.app.rag.DocumentStore
import com.localllm.app.server.installTestServer
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.util.concurrent.atomic.AtomicLong
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DocumentsRouteTest {

    private val gson = Gson()
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val lastActivity = AtomicLong(0)
    private lateinit var docStore: DocumentStore

    @Before fun setUp() {
        Settings.prefs(context).edit().clear().apply()
        docStore = DocumentStore(context)
        docStore.listTenants().forEach { docStore.deleteTenant(it.tenantId) }
    }

    @After fun tearDown() { docStore.close() }

    private fun fakeRegistry(): EmbeddingRegistry {
        val svc: EmbeddingService = mock {
            onBlocking { embed(any()) } doReturn listOf(
                FloatArray(384) { 0.1f } to 2,
                FloatArray(384) { 0.2f } to 2,
            )
        }
        return mock<EmbeddingRegistry> {
            on { acquire(any()) } doReturn svc
        }
    }

    @Test
    fun `POST documents stores chunks and reflects them in GET`() = testApplication {
        installTestServer {
            documentsRoute(context, fakeRegistry(), { docStore }, lastActivity)
        }
        val text = "Paragraph one.\n\nParagraph two."
        val postResp = client.post("/v1/documents") {
            contentType(ContentType.Application.Json)
            header("X-Client-Id", "alice")
            setBody("""{"id":"doc-1","text":${gson.toJson(text)},"model":"bge-small-en-v1.5"}""")
        }
        assertEquals(HttpStatusCode.OK, postResp.status)
        assertEquals("alice", postResp.headers["X-Tenant-Id"])

        val getResp = client.get("/v1/documents") { header("X-Client-Id", "alice") }
        val obj = gson.fromJson(getResp.bodyAsText(), Map::class.java) as Map<*, *>
        val list = obj["data"] as List<Map<*, *>>
        assertEquals(1, list.size)
        assertEquals("doc-1", list[0]["document_id"])
        assertEquals("alice", obj["tenant_id"])
    }

    @Test
    fun `POST documents requires id text and model`() = testApplication {
        installTestServer {
            documentsRoute(context, fakeRegistry(), { docStore }, lastActivity)
        }
        val resp = client.post("/v1/documents") {
            contentType(ContentType.Application.Json)
            setBody("""{"id":"","text":"","model":""}""")
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }

    @Test
    fun `POST documents 400 on malformed JSON`() = testApplication {
        installTestServer {
            documentsRoute(context, fakeRegistry(), { docStore }, lastActivity)
        }
        val resp = client.post("/v1/documents") {
            contentType(ContentType.Application.Json); setBody("not json")
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }

    @Test
    fun `DELETE document removes only that document`() = testApplication {
        installTestServer {
            documentsRoute(context, fakeRegistry(), { docStore }, lastActivity)
        }
        client.post("/v1/documents") {
            contentType(ContentType.Application.Json); header("X-Client-Id", "alice")
            setBody("""{"id":"keep","text":"first text","model":"m"}""")
        }
        client.post("/v1/documents") {
            contentType(ContentType.Application.Json); header("X-Client-Id", "alice")
            setBody("""{"id":"drop","text":"other text","model":"m"}""")
        }
        val del = client.delete("/v1/documents/drop") { header("X-Client-Id", "alice") }
        assertEquals(HttpStatusCode.OK, del.status)
        val remaining = (gson.fromJson(
            client.get("/v1/documents") { header("X-Client-Id", "alice") }.bodyAsText(),
            Map::class.java,
        ) as Map<*, *>)["data"] as List<*>
        assertEquals(1, remaining.size)
    }

    @Test
    fun `GET tenants summarises across tenants without auth bleed`() = testApplication {
        installTestServer {
            documentsRoute(context, fakeRegistry(), { docStore }, lastActivity)
        }
        client.post("/v1/documents") {
            contentType(ContentType.Application.Json); header("X-Client-Id", "alice")
            setBody("""{"id":"a","text":"alice doc","model":"m"}""")
        }
        client.post("/v1/documents") {
            contentType(ContentType.Application.Json); header("X-Client-Id", "bob")
            setBody("""{"id":"b","text":"bob doc","model":"m"}""")
        }
        val resp = client.get("/v1/tenants")
        val arr = (gson.fromJson(resp.bodyAsText(), Map::class.java) as Map<*, *>)["data"] as List<Map<*, *>>
        val tenants = arr.map { it["tenant_id"] }.toSet()
        assertTrue("expected alice + bob in $tenants", tenants.containsAll(listOf("alice", "bob")))
    }

    @Test
    fun `search rejects empty query`() = testApplication {
        installTestServer {
            documentsRoute(context, fakeRegistry(), { docStore }, lastActivity)
        }
        val resp = client.post("/v1/search") {
            contentType(ContentType.Application.Json)
            setBody("""{"query":"","model":"m"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }
}
