package com.postsaimanager.core.designsystem.component

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle

/**
 * Puts [text] on the clipboard. The one place the app copies scanned text, so every copy behaves alike.
 *
 * Marked sensitive on Android 13+ ([ClipDescription.EXTRA_IS_SENSITIVE]): scanned letters hold IBANs, names and addresses,
 * and the system then hides the clip's preview instead of showing it on screen.
 */
fun copyScannedText(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText(label, text)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    }
    clipboard.setPrimaryClip(clip)
}
