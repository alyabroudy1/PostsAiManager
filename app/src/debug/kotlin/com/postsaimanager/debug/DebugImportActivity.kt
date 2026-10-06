package com.postsaimanager.debug

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.postsaimanager.MainActivity
import com.postsaimanager.core.domain.usecase.CreateDocumentFromPagesUseCase
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * DEBUG BUILDS ONLY. Started by the device harness:
 * `am start -n com.postsaimanager.debug/com.postsaimanager.debug.DebugImportActivity --esa files page-1.jpg,page-2.jpg`
 * after pushing the files to `/sdcard/Android/data/com.postsaimanager.debug/files/debug-import/`. See documentation/05-test-harness.md.
 */
@AndroidEntryPoint
class DebugImportActivity : ComponentActivity() {

    @Inject
    lateinit var createDocument: CreateDocumentFromPagesUseCase

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val names = intent.getStringArrayExtra(EXTRA_FILES)?.toList()
            ?: intent.getStringExtra(EXTRA_FILES)?.split(',')?.map { it.trim() }.orEmpty()
        val inbox = getExternalFilesDir(INBOX)
        if (inbox == null) {
            Log.w(TAG, "rejected: no external files dir")
            finish()
            return
        }
        val importer = DebugImporter(inbox, java.io.File(filesDir, "debug-import-pages"), createDocument)
        lifecycleScope.launch {
            when (val outcome = importer.import(names)) {
                is DebugImporter.Outcome.Imported -> Log.i(TAG, "imported document=${outcome.documentId} pages=${outcome.pages}")
                is DebugImporter.Outcome.Rejected -> Log.w(TAG, "rejected: ${outcome.reason}")
            }
            startActivity(Intent(this@DebugImportActivity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
        }
    }

    private companion object {
        const val TAG = "DebugImport"
        const val EXTRA_FILES = "files"
        const val INBOX = "debug-import"
    }
}
