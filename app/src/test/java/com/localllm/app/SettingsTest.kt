package com.localllm.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests the typed wrapper around SharedPreferences. We run under Robolectric so
 * we get a real SharedPreferences implementation (not a mock) — the value of
 * these tests is largely in exercising the coercion / sanitization logic on
 * the setters.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        Settings.prefs(context).edit().clear().apply()
    }

    /* ---------- port ---------- */

    @Test
    fun `default port is 8080`() {
        assertEquals(8080, Settings.port(context))
    }

    @Test
    fun `setPort persists valid value`() {
        Settings.setPort(context, 9000)
        assertEquals(9000, Settings.port(context))
    }

    @Test
    fun `setPort clamps below privileged range`() {
        Settings.setPort(context, 80)
        assertEquals(1024, Settings.port(context))
    }

    @Test
    fun `setPort clamps above 65535`() {
        Settings.setPort(context, 100_000)
        assertEquals(65535, Settings.port(context))
    }

    /* ---------- selected model ---------- */

    @Test
    fun `default selected model is AICore Gemini Nano`() {
        assertEquals("gemini-nano-aicore", Settings.selectedModelId(context))
        assertEquals(Settings.DEFAULT_MODEL_ID, Settings.selectedModelId(context))
    }

    @Test
    fun `setSelectedModelId persists the value`() {
        Settings.setSelectedModelId(context, "gemma-4-e2b")
        assertEquals("gemma-4-e2b", Settings.selectedModelId(context))
    }

    @Test
    fun `setSelectedModelId trims whitespace`() {
        Settings.setSelectedModelId(context, "  gemma-4-e4b  ")
        assertEquals("gemma-4-e4b", Settings.selectedModelId(context))
    }

    @Test
    fun `setSelectedModelId empty falls back to default`() {
        Settings.setSelectedModelId(context, "")
        assertEquals(Settings.DEFAULT_MODEL_ID, Settings.selectedModelId(context))
    }

    /* ---------- bindHost ---------- */

    @Test
    fun `bindHost is localhost when bindLan is off`() {
        assertEquals("127.0.0.1", Settings.bindHost(context))
    }

    @Test
    fun `bindHost is 0_0_0_0 when bindLan is on`() {
        Settings.setBindLan(context, true)
        assertEquals("0.0.0.0", Settings.bindHost(context))
    }

    /* ---------- api key ---------- */

    @Test
    fun `setApiKey trims whitespace`() {
        Settings.setApiKey(context, "  abc  ")
        assertEquals("abc", Settings.apiKey(context))
    }

    @Test
    fun `empty api key disables auth implicitly`() {
        Settings.setApiKey(context, "")
        assertTrue(Settings.apiKey(context).isEmpty())
    }

    /* ---------- numeric ranges ---------- */

    @Test
    fun `setTemperature clamps to 0_2`() {
        Settings.setTemperature(context, -1f)
        assertEquals(0f, Settings.temperature(context), 0.001f)
        Settings.setTemperature(context, 5f)
        assertEquals(2f, Settings.temperature(context), 0.001f)
    }

    @Test
    fun `setTopK clamps below 1`() {
        Settings.setTopK(context, 0)
        assertEquals(1, Settings.topK(context))
    }

    @Test
    fun `setMaxTokens clamps below 64`() {
        Settings.setMaxTokens(context, 1)
        assertEquals(64, Settings.maxTokens(context))
    }

    @Test
    fun `setMaxQueueDepth clamps to allowed range`() {
        Settings.setMaxQueueDepth(context, 0)
        assertEquals(1, Settings.maxQueueDepth(context))
        Settings.setMaxQueueDepth(context, 10_000)
        assertEquals(100, Settings.maxQueueDepth(context))
    }

    @Test
    fun `setRequestTimeoutMs enforces minimum`() {
        Settings.setRequestTimeoutMs(context, 100L)
        assertEquals(5_000L, Settings.requestTimeoutMs(context))
    }

    /* ---------- custom URLs ---------- */

    @Test
    fun `customModelUrls round-trips a list`() {
        Settings.setCustomModelUrls(context, listOf("http://a.com/m.task", "http://b.com/m2.task"))
        val urls = Settings.customModelUrls(context)
        assertEquals(2, urls.size)
        assertEquals("http://a.com/m.task", urls[0])
        assertEquals("http://b.com/m2.task", urls[1])
    }

    @Test
    fun `customModelUrls filters out blank lines`() {
        Settings.setCustomModelUrls(context, listOf("a", "", "  ", "b"))
        val urls = Settings.customModelUrls(context)
        assertEquals(listOf("a", "b"), urls)
    }

    /* ---------- toggles ---------- */

    @Test
    fun `startOnBoot defaults to true`() {
        assertTrue(Settings.startOnBoot(context))
    }

    @Test
    fun `allowCors defaults to false`() {
        assertFalse(Settings.allowCors(context))
    }
}
