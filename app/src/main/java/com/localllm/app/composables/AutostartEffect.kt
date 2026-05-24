package com.localllm.app.composables

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.localllm.app.ServerState
import com.localllm.app.Settings

/**
 * Triggers [onStart] when autostart is enabled, notification permission has
 * been granted, and the server is currently stopped.
 *
 * Autostart used to require a `.litertlm` on disk (`existingModels.isNotEmpty()`)
 * but AICore (the default model) is virtual — a fresh install with zero
 * local files is a valid serve-ready state. Dropped that precondition.
 *
 * Two trigger surfaces, both preserved from the original MainActivity:
 *
 *  - [LaunchedEffect]: fires once on first composition after the permission
 *    grant flips to true (initial install flow).
 *  - [LifecycleEventObserver] on ON_START: covers returning to the app
 *    after the server was stopped externally.
 *
 * The caller is responsible for requesting POST_NOTIFICATIONS itself —
 * this composable only reads the granted flag.
 */
@Composable
fun AutostartEffect(
    hasNotificationPermission: Boolean,
    onStart: () -> Unit,
) {
    val context = LocalContext.current

    LaunchedEffect(hasNotificationPermission) {
        if (hasNotificationPermission &&
            Settings.autostart(context) &&
            ServerState.status.value == ServerState.Status.STOPPED
        ) {
            onStart()
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START &&
                ServerState.status.value == ServerState.Status.STOPPED &&
                Settings.autostart(context) &&
                hasNotificationPermission
            ) {
                onStart()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}
