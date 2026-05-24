package com.localllm.app.server.routes

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import com.localllm.app.Settings
import com.localllm.app.inference.EmbeddingRegistry
import com.localllm.app.inference.aicore.AICoreEngine
import com.localllm.app.server.installTestServer
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ModelsRouteTest {

    private val gson = Gson()
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var modelDir: File

    @Before fun setUp() {
        Settings.prefs(context).edit().clear().apply()
        modelDir = context.getExternalFilesDir(null)!!
        modelDir.listFiles()?.forEach { it.delete() }
    }

    @Test
    fun `requires auth when api key is configured`() = testApplication {
        Settings.setApiKey(context, "sekret")
        installTestServer { modelsRoute(context, EmbeddingRegistry(context)) }
        val resp = client.get("/v1/models")
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
    }

    @Test
    fun `empty models dir still exposes the virtual AICore model`() = testApplication {
        installTestServer { modelsRoute(context, EmbeddingRegistry(context)) }
        val resp = client.get("/v1/models")
        assertEquals(HttpStatusCode.OK, resp.status)
        val obj = gson.fromJson(resp.bodyAsText(), Map::class.java) as Map<*, *>
        val data = obj["data"] as List<Map<*, *>>
        // At minimum the AICore virtual entry is always present.
        assertTrue(data.any { it["id"] == AICoreEngine.MODEL_ID })
    }

    @Test
    fun `litertlm files on disk are listed as models`() = testApplication {
        File(modelDir, "fake-model.litertlm").writeBytes(ByteArray(100))
        installTestServer { modelsRoute(context, EmbeddingRegistry(context)) }
        val resp = client.get("/v1/models")
        val data = (gson.fromJson(resp.bodyAsText(), Map::class.java) as Map<*, *>)["data"] as List<Map<*, *>>
        assertTrue("expected fake-model in $data", data.any { it["id"] == "fake-model" })
    }

    @Test
    fun `onnx files without matching vocab are not listed as embedding models`() = testApplication {
        File(modelDir, "lonely.onnx").writeBytes(ByteArray(50))
        installTestServer { modelsRoute(context, EmbeddingRegistry(context)) }
        val data = (gson.fromJson(client.get("/v1/models").bodyAsText(), Map::class.java) as Map<*, *>)["data"] as List<Map<*, *>>
        assertTrue(data.none { it["id"] == "lonely" })
    }

    @Test
    fun `onnx with vocab-txt sibling appears in the list`() = testApplication {
        File(modelDir, "embed.onnx").writeBytes(ByteArray(50))
        File(modelDir, "embed-vocab.txt").writeText("[PAD]\n[UNK]\n")
        installTestServer { modelsRoute(context, EmbeddingRegistry(context)) }
        val data = (gson.fromJson(client.get("/v1/models").bodyAsText(), Map::class.java) as Map<*, *>)["data"] as List<Map<*, *>>
        assertTrue("expected embed in $data", data.any { it["id"] == "embed" })
    }

    @Test
    fun `bearer header lets the call through when api key is set`() = testApplication {
        Settings.setApiKey(context, "sekret")
        installTestServer { modelsRoute(context, EmbeddingRegistry(context)) }
        val resp = client.get("/v1/models") { header("Authorization", "Bearer sekret") }
        assertEquals(HttpStatusCode.OK, resp.status)
    }
}
