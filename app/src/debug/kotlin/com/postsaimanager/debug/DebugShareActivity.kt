package com.postsaimanager.debug

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log

/**
 * DEBUG BUILDS ONLY. `am` cannot put a list of URIs into an intent, so this starts the `ACTION_SEND_MULTIPLE` share of the named
 * files itself, exactly as another app's share sheet would (`ImportActivity`, URIs of [DebugShareProvider]):
 * `am start -n com.postsaimanager.debug/com.postsaimanager.debug.DebugShareActivity --esa files a.jpg,b.jpg`.
 * See documentation/05-test-harness.md.
 */
class DebugShareActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val names = intent.getStringArrayExtra("files")?.toList()
            ?: intent.getStringExtra("files")?.split(',')?.map { it.trim() }.orEmpty()
        val uris = names.filter(DebugImporter::isPlainName).map { Uri.parse("content://$packageName.testshare/$it") }
        if (uris.isEmpty()) {
            Log.w(TAG, "rejected: no plain file names")
        } else {
            val share = Intent(Intent.ACTION_SEND_MULTIPLE)
                .setComponent(ComponentName(this, "com.postsaimanager.importing.ImportActivity"))
                .setType(DebugShareProvider.mimeTypeOf(names.first()) ?: "image/*")
                .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            startActivity(share)
            Log.i(TAG, "shared ${uris.size} files")
        }
        finish()
    }

    private companion object {
        const val TAG = "DebugShare"
    }
}
