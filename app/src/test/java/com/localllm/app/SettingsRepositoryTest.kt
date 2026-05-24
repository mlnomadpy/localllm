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
 * [SettingsRepository] is exercised indirectly by [SettingsTest] (through the
 * synchronous facade). These tests focus on what the facade doesn't — the
 * StateFlow surface that Compose UI binds to, and the cross-cutting
 * clearAll / wipeForTesting behaviour.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsRepositoryTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        Settings.prefs(context).edit().clear().apply()
    }

    @Test
    fun `repo singleton returns the same instance on repeated get`() {
        val a = SettingsRepository.get(context)
        val b = SettingsRepository.get(context)
        assertTrue("get() must return the cached singleton", a === b)
    }

    @Test
    fun `setters update the StateFlow value synchronously`() {
        val repo = SettingsRepository.get(context)
        repo.setMaxTokens(2048)
        assertEquals(2048, repo.maxTokens.value)
        repo.setTemperature(0.5f)
        assertEquals(0.5f, repo.temperature.value, 1e-6f)
    }

    @Test
    fun `clamping on the repository matches clamping on the facade`() {
        val repo = SettingsRepository.get(context)
        repo.setPort(80)
        assertEquals(1024, repo.port.value)
        assertEquals(1024, Settings.port(context))
        repo.setPort(100_000)
        assertEquals(65535, repo.port.value)
        assertEquals(65535, Settings.port(context))
    }

    @Test
    fun `clearAll resets every flow to defaults`() {
        val repo = SettingsRepository.get(context)
        repo.setPort(9000)
        repo.setApiKey("abc")
        repo.setSelectedModelId("gemma-4-e2b")
        repo.setAllowCors(true)
        repo.clearAll()
        assertEquals(Settings.DEFAULT_PORT, repo.port.value)
        assertEquals("", repo.apiKey.value)
        assertEquals(Settings.DEFAULT_MODEL_ID, repo.selectedModelId.value)
        assertFalse(repo.allowCors.value)
    }

    @Test
    fun `bindHost helper mirrors the facade`() {
        val repo = SettingsRepository.get(context)
        assertEquals("127.0.0.1", repo.bindHost())
        repo.setBindLan(true)
        assertEquals("0.0.0.0", repo.bindHost())
        assertEquals("0.0.0.0", Settings.bindHost(context))
    }

    @Test
    fun `customModelUrls round-trip through the joined string representation`() {
        val repo = SettingsRepository.get(context)
        repo.setCustomModelUrls(listOf("https://a", "https://b", "", "  ", "https://c"))
        // Blanks are filtered on read.
        assertEquals(listOf("https://a", "https://b", "https://c"), repo.customModelUrls.value)
    }

    @Test
    fun `setApiKey trims surrounding whitespace`() {
        val repo = SettingsRepository.get(context)
        repo.setApiKey("   sk-12345   ")
        assertEquals("sk-12345", repo.apiKey.value)
    }

    @Test
    fun `setRateLimit values clamp into allowed ranges`() {
        val repo = SettingsRepository.get(context)
        repo.setRateLimitPerSec(-5.0)
        assertEquals(0.0, repo.rateLimitPerSec.value, 1e-9)
        repo.setRateLimitPerSec(99_999.0)
        assertEquals(1000.0, repo.rateLimitPerSec.value, 1e-9)
        repo.setRateLimitBurst(0.5)
        assertEquals(1.0, repo.rateLimitBurst.value, 1e-9)
    }

    @Test
    fun `firstFlush completes synchronously during construction`() {
        // The repo constructor uses runBlocking on its DataStore, so by the
        // time get() returns, firstFlush has already resolved.
        val repo = SettingsRepository.get(context)
        assertTrue(repo.firstFlush.isCompleted)
    }
}
