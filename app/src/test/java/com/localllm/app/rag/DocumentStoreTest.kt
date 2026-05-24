package com.localllm.app.rag

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [DocumentStore] is the on-device RAG corpus. The recent perf pass switched
 * `listDocuments` / `listTenants` from full-row reads to PropertyQuery
 * projections; these tests pin the result shape (counts + distinct ids)
 * stays unchanged, and exercise the multi-tenant cleanup paths.
 *
 * Robolectric provides a real filesystem so ObjectBox can open a per-test
 * store under the app's private data dir. The [After] hook closes it; the
 * next test gets a fresh one.
 */
@RunWith(RobolectricTestRunner::class)
class DocumentStoreTest {

    private lateinit var context: Context
    private lateinit var store: DocumentStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        store = DocumentStore(context)
        // ObjectBox files persist across tests in the same JVM; wipe state at
        // start of each test so we get a deterministic count.
        store.listTenants().forEach { store.deleteTenant(it.tenantId) }
    }

    @After
    fun tearDown() {
        store.close()
    }

    private fun chunk(
        tenantId: String,
        documentId: String,
        chunkIndex: Int = 0,
        text: String = "snippet",
        embeddingModel: String = "bge-small-en-v1.5",
        embedding: FloatArray = FloatArray(384) { it.toFloat() },
    ): DocumentChunk = DocumentChunk(
        tenantId = tenantId,
        documentId = documentId,
        chunkIndex = chunkIndex,
        text = text,
        embeddingModel = embeddingModel,
        embedding = embedding,
    )

    @Test
    fun `put and listDocuments returns one summary per distinct document id`() {
        store.put(listOf(
            chunk("t1", "doc-a", 0),
            chunk("t1", "doc-a", 1),
            chunk("t1", "doc-a", 2),
            chunk("t1", "doc-b", 0),
        ))
        val docs = store.listDocuments("t1")
        assertEquals(2, docs.size)
        val a = docs.first { it.documentId == "doc-a" }
        val b = docs.first { it.documentId == "doc-b" }
        assertEquals(3, a.chunkCount)
        assertEquals("bge-small-en-v1.5", a.embeddingModel)
        assertEquals(1, b.chunkCount)
    }

    @Test
    fun `listDocuments is empty for a fresh tenant`() {
        assertTrue(store.listDocuments("never-seen").isEmpty())
    }

    @Test
    fun `listDocuments is scoped to the requested tenant`() {
        store.put(listOf(
            chunk("alice", "secret", 0),
            chunk("alice", "secret", 1),
            chunk("bob", "secret", 0),
        ))
        assertEquals(1, store.listDocuments("alice").size)
        assertEquals(2, store.listDocuments("alice").first().chunkCount)
        assertEquals(1, store.listDocuments("bob").first().chunkCount)
    }

    @Test
    fun `listTenants aggregates chunk + distinct document counts per tenant`() {
        store.put(listOf(
            chunk("alice", "a", 0),
            chunk("alice", "a", 1),
            chunk("alice", "b", 0),
            chunk("bob", "x", 0),
        ))
        val tenants = store.listTenants().associateBy { it.tenantId }
        assertEquals(2, tenants["alice"]!!.documentCount)
        assertEquals(3, tenants["alice"]!!.chunkCount)
        assertEquals(1, tenants["bob"]!!.documentCount)
        assertEquals(1, tenants["bob"]!!.chunkCount)
    }

    @Test
    fun `deleteDocument removes only chunks matching tenant and document id`() {
        store.put(listOf(
            chunk("alice", "doc-a", 0),
            chunk("alice", "doc-a", 1),
            chunk("alice", "doc-b", 0),
            chunk("bob", "doc-a", 0),
        ))
        val removed = store.deleteDocument("alice", "doc-a")
        assertEquals(2, removed)
        assertEquals(1, store.listDocuments("alice").size)
        // bob's doc-a is untouched.
        assertEquals(1, store.listDocuments("bob").size)
    }

    @Test
    fun `deleteDocument returns 0 for unknown ids`() {
        assertEquals(0, store.deleteDocument("alice", "nope"))
    }

    @Test
    fun `deleteTenant removes every chunk owned by the tenant`() {
        store.put(listOf(
            chunk("alice", "doc-a", 0),
            chunk("alice", "doc-b", 0),
            chunk("bob", "doc-a", 0),
        ))
        val removed = store.deleteTenant("alice")
        assertEquals(2, removed)
        assertTrue(store.listDocuments("alice").isEmpty())
        assertEquals(1, store.listDocuments("bob").size)
    }

    @Test
    fun `byDocument returns chunks in chunk-index order`() {
        store.put(listOf(
            chunk("t", "d", 2, text = "c"),
            chunk("t", "d", 0, text = "a"),
            chunk("t", "d", 1, text = "b"),
        ))
        val out = store.byDocument("t", "d")
        assertEquals(listOf("a", "b", "c"), out.map { it.text })
    }

    @Test
    fun `count reflects the total number of chunks across tenants`() {
        store.put(listOf(
            chunk("a", "x", 0),
            chunk("a", "x", 1),
            chunk("b", "y", 0),
        ))
        assertEquals(3L, store.count())
    }
}
