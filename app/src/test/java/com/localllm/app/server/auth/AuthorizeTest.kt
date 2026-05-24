package com.localllm.app.server.auth

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.localllm.app.Settings
import com.localllm.app.server.installTestServer
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.testing.testApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AuthorizeTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before fun setUp() {
        Settings.prefs(context).edit().clear().apply()
    }

    @Test
    fun `empty api key allows the call through`() = testApplication {
        Settings.setApiKey(context, "")
        installTestServer {
            get("/p") {
                if (!authorize(call, context)) return@get
                call.respondText("ok")
            }
        }
        val resp = client.get("/p")
        assertEquals(HttpStatusCode.OK, resp.status)
        assertEquals("ok", resp.bodyAsText())
    }

    @Test
    fun `configured key requires matching bearer header`() = testApplication {
        Settings.setApiKey(context, "sekret")
        installTestServer {
            get("/p") {
                if (!authorize(call, context)) return@get
                call.respondText("ok")
            }
        }
        val resp = client.get("/p")
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
        // 401 includes the WWW-Authenticate Bearer challenge.
        assertEquals("Bearer", resp.headers["WWW-Authenticate"])
        assertTrue(
            "expected error JSON, got '${resp.bodyAsText()}'",
            resp.bodyAsText().contains("invalid_api_key"),
        )
    }

    @Test
    fun `wrong bearer is rejected`() = testApplication {
        Settings.setApiKey(context, "sekret")
        installTestServer {
            get("/p") {
                if (!authorize(call, context)) return@get
                call.respondText("ok")
            }
        }
        val resp = client.get("/p") { header("Authorization", "Bearer nope") }
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
    }

    @Test
    fun `correct bearer passes through`() = testApplication {
        Settings.setApiKey(context, "sekret")
        installTestServer {
            get("/p") {
                if (!authorize(call, context)) return@get
                call.respondText("ok")
            }
        }
        val resp = client.get("/p") { header("Authorization", "Bearer sekret") }
        assertEquals(HttpStatusCode.OK, resp.status)
    }

    @Test
    fun `header without Bearer prefix is rejected`() = testApplication {
        Settings.setApiKey(context, "sekret")
        installTestServer {
            get("/p") {
                if (!authorize(call, context)) return@get
                call.respondText("ok")
            }
        }
        val resp = client.get("/p") { header("Authorization", "sekret") }
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
    }
}
