package com.localllm.app.server

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Robolectric's NsdManager shadow accepts register/unregister calls without
 * actually advertising on the network. We can't introspect the internal
 * service registry from the public Shadow API, so these tests focus on
 * "calling start / stop never throws" — which historically has been the
 * fragile bit (NsdManager hostile to repeated unregister, threading
 * subtleties on older API levels).
 */
@RunWith(RobolectricTestRunner::class)
class NsdBroadcasterTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `start on a routable host succeeds`() {
        val b = NsdBroadcaster(context)
        b.start(host = "192.168.1.42", port = 8080)
        b.stop()
    }

    @Test
    fun `start on loopback host is a no-op and does not throw`() {
        val b = NsdBroadcaster(context)
        b.start(host = "127.0.0.1", port = 8080)
        b.stop() // safe even though start was a no-op
    }

    @Test
    fun `stop without prior start is safe`() {
        val b = NsdBroadcaster(context)
        b.stop()
    }

    @Test
    fun `repeated start replaces the prior registration without throwing`() {
        val b = NsdBroadcaster(context)
        b.start("192.168.1.10", 8000)
        b.start("192.168.1.10", 8001)
        b.stop()
    }

    @Test
    fun `repeated stop is idempotent`() {
        val b = NsdBroadcaster(context)
        b.start("192.168.1.42", 9000)
        b.stop()
        b.stop()
        b.stop()
    }
}
