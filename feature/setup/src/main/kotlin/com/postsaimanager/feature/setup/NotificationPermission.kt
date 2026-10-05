package com.postsaimanager.feature.setup

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/** Whether the notification permission is still to be asked: only Android 13+ has it, and only until it is granted. */
internal fun shouldAskNotificationPermission(sdkInt: Int, granted: Boolean): Boolean =
    sdkInt >= Build.VERSION_CODES.TIRAMISU && !granted

@Composable
internal fun isNotificationPermissionMissing(): Boolean {
    val context = LocalContext.current
    return shouldAskNotificationPermission(
        Build.VERSION.SDK_INT,
        granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED,
    )
}

/**
 * Wraps [proceed] (starting the download): asks for the notification permission first when it is still missing, and runs
 * [proceed] after the answer whatever it is. A denial never blocks the download; the progress notification is just not shown.
 */
@Composable
internal fun rememberAskNotificationsThen(proceed: () -> Unit): () -> Unit {
    val currentProceed = rememberUpdatedState(proceed)
    val missing = isNotificationPermissionMissing()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        currentProceed.value()
    }
    return {
        if (missing) launcher.launch(Manifest.permission.POST_NOTIFICATIONS) else currentProceed.value()
    }
}
