package com.localllm.app.server.auth

import android.content.Context
import com.localllm.app.ErrorDetails
import com.localllm.app.ErrorResponse
import com.localllm.app.Settings
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respond

/**
 * Bearer-token gate. Returns `true` when the request is allowed through.
 * On failure, writes a 401 + WWW-Authenticate header and returns `false`.
 *
 * Configuration is read from [Settings.apiKey]: empty string disables auth
 * entirely (default — convenient for local development; users opt into
 * keyed access via the Security section of the Settings tab).
 */
suspend fun authorize(call: ApplicationCall, context: Context): Boolean {
    val configured = Settings.apiKey(context)
    if (configured.isEmpty()) return true
    val header = call.request.headers["Authorization"]
    val ok = header != null && header.startsWith("Bearer ") &&
        header.substring(7).trim() == configured
    if (!ok) {
        call.response.header("WWW-Authenticate", "Bearer")
        call.respond(
            HttpStatusCode.Unauthorized,
            ErrorResponse(ErrorDetails("Invalid or missing API key", "invalid_api_key", 401))
        )
    }
    return ok
}
