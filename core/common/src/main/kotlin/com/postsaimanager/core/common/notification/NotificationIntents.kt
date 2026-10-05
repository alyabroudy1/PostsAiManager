package com.postsaimanager.core.common.notification

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * The one owner of "what happens when a notification is tapped": a [PendingIntent] that opens the app's main activity on a
 * [NotificationRoute]. The activity is found through the package's launch intent, so no module needs to know its class.
 *
 * The intent is an explicit `ACTION_VIEW` on that component (not the launcher `MAIN` intent), so an already running app receives
 * it in `onNewIntent` instead of the system just bringing the task forward. It goes through the app lock like any launch.
 */
object NotificationIntents {

    /** [requestCode] must differ per target, or `FLAG_UPDATE_CURRENT` would redirect an earlier notification's intent. */
    fun requestCode(route: NotificationRoute): Int = route.toUri().hashCode()

    fun contentIntent(context: Context, route: NotificationRoute): PendingIntent? {
        val component = context.packageManager.getLaunchIntentForPackage(context.packageName)?.component ?: return null
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(route.toUri()))
            .setComponent(component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            context,
            requestCode(route),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
