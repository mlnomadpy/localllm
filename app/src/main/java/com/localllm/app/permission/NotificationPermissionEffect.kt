package com.localllm.app.permission

import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.LocalContext

/**
 * Holds the runtime POST_NOTIFICATIONS permission state plus a launch fn the
 * caller can invoke to request it. On API < 33 [granted] is always true and
 * [request] is a no-op.
 */
class NotificationPermissionState internal constructor(
    private val grantedState: MutableState<Boolean>,
    private val launcher: ManagedActivityResultLauncher<String, Boolean>,
) {
    val granted: Boolean get() = grantedState.value

    fun request() {
        if (Build.VERSION.SDK_INT >= 33 && !grantedState.value) {
            launcher.launch("android.permission.POST_NOTIFICATIONS")
        }
    }
}

/**
 * Mirrors the original inline permission plumbing from MainActivity: seeds
 * the initial granted flag from [ContextCompat.checkSelfPermission] (true
 * on pre-33) and wires a [ActivityResultContracts.RequestPermission]
 * launcher whose result feeds the granted state.
 */
@Composable
fun rememberNotificationPermissionState(): NotificationPermissionState {
    val context = LocalContext.current
    val granted = remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= 33) {
                ContextCompat.checkSelfPermission(
                    context,
                    "android.permission.POST_NOTIFICATIONS"
                ) == PackageManager.PERMISSION_GRANTED
            } else true
        )
    }
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted.value = it }
    )
    return remember(launcher) { NotificationPermissionState(granted, launcher) }
}
