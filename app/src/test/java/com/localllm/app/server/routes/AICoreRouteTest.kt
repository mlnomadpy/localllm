package com.localllm.app.server.routes

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import com.localllm.app.Settings
import com.localllm.app.inference.aicore.AICoreEngine
import com.localllm.app.server.installTestServer
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * AICore isn't reachable from the JVM — `checkStatusCode()` throws because
 * no Pixel system service exists. The route is built to surface that error
 * verbatim in the JSON body, not 500. These tests pin that contract.
 */
@RunWith(RobolectricTestRunner::class)
class AICoreRouteTest {

    private val gson = Gson()
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before fun setUp() {
        Settings.prefs(context).edit().clear().apply()
    }

    @Test
    fun `status endpoint returns model identity and device info even when probe fails`() = testApplication {
        installTestServer { aiCoreRoute(context) }
        val resp = client.get("/v1/aicore/status")
        assertEquals(HttpStatusCode.OK, resp.status)
        val obj = gson.fromJson(resp.bodyAsText(), Map::class.java) as Map<*, *>
        assertEquals(AICoreEngine.MODEL_ID, obj["model_id"])
        assertEquals(false, obj["available"])
        assertNotNull(obj["soc_model"])
        assertNotNull(obj["device"])
    }

    @Test
    fun `requires auth when api key is configured`() = testApplication {
        Settings.setApiKey(context, "sekret")
        installTestServer { aiCoreRoute(context) }
        val resp = client.get("/v1/aicore/status")
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
    }

    @Test
    fun `probe=all path returns a configs section`() = testApplication {
        installTestServer { aiCoreRoute(context) }
        val resp = client.get("/v1/aicore/status?probe=all")
        assertEquals(HttpStatusCode.OK, resp.status)
        val obj = gson.fromJson(resp.bodyAsText(), Map::class.java) as Map<*, *>
        assertNotNull("expected configs section", obj["configs"])
    }
}
