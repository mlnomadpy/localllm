package com.localllm.app.server

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import com.localllm.app.LogManager

/**
 * Advertise the embedded HTTP API on the local network via mDNS / DNS-SD so
 * desktop clients can auto-discover it. Service type is `_localllm._tcp.` —
 * a custom subtype rather than `_http._tcp.` to keep this app distinguishable
 * from every other web server on the LAN.
 *
 * One [NsdBroadcaster] per running server. [start] returns immediately; the
 * registration result is reported via [NsdManager.RegistrationListener]
 * callbacks and logged. [stop] is idempotent.
 *
 * No-op when the server is bound to loopback only — there's nothing for a
 * peer on the LAN to connect to.
 */
class NsdBroadcaster(private val appContext: Context) {

    private val nsd: NsdManager? = try {
        appContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
    } catch (e: Throwable) {
        LogManager.w("NsdBroadcaster", "NSD service unavailable: ${e.message}")
        null
    }

    @Volatile private var listener: NsdManager.RegistrationListener? = null

    /**
     * Publish `_localllm._tcp` on [port]. [host] is informational — used in
     * TXT attributes so a client can confirm which interface the server was
     * actually bound to. Idempotent: a second call replaces the prior
     * registration.
     */
    fun start(host: String, port: Int) {
        val mgr = nsd ?: return
        if (host == "127.0.0.1") {
            LogManager.i("NsdBroadcaster", "Skipping broadcast (loopback-only bind)")
            return
        }
        stop()
        val info = NsdServiceInfo().apply {
            serviceName = "LocalLLM"
            serviceType = "_localllm._tcp."
            this.port = port
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                setAttribute("api", "openai-compat")
                setAttribute("path", "/v1")
                setAttribute("health", "/health")
            }
        }
        val newListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                LogManager.i(
                    "NsdBroadcaster",
                    "Registered ${serviceInfo.serviceName} on ${serviceInfo.serviceType}:${serviceInfo.port}",
                )
            }
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                LogManager.w("NsdBroadcaster", "Registration failed (code=$errorCode)")
            }
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                LogManager.i("NsdBroadcaster", "Unregistered ${serviceInfo.serviceName}")
            }
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                LogManager.w("NsdBroadcaster", "Unregistration failed (code=$errorCode)")
            }
        }
        listener = newListener
        try {
            mgr.registerService(info, NsdManager.PROTOCOL_DNS_SD, newListener)
        } catch (e: Throwable) {
            LogManager.w("NsdBroadcaster", "Register threw: ${e.message}")
            listener = null
        }
    }

    fun stop() {
        val mgr = nsd ?: return
        val l = listener ?: return
        listener = null
        try {
            mgr.unregisterService(l)
        } catch (e: Throwable) {
            // Common if the service was never successfully registered or has
            // already been torn down.
            LogManager.d("NsdBroadcaster", "Unregister threw: ${e.message}")
        }
    }
}
