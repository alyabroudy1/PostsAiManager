package com.postsaimanager.debug

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/**
 * DEBUG BUILDS ONLY (lives in `app/src/debug`, so a release build has neither this class nor its `<provider>` entry). Serves the files
 * the test harness pushed into the `debug-import` folder as `content://<applicationId>.testshare/<file name>`, so the share and
 * "Open with" intents of the import feature can be tried with `am start` and no other app's file browser, which could show real
 * files. Read-only, plain file names only, and not exported: only this app (the `ImportActivity` it starts) can read it.
 * See documentation/05-test-harness.md.
 */
class DebugShareProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String? = uri.lastPathSegment?.let(::mimeTypeOf)

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("read-only")
        val file = resolve(uri) ?: throw FileNotFoundException(uri.toString())
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        val file = resolve(uri) ?: return null
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns).apply {
            addRow(columns.map { if (it == OpenableColumns.SIZE) file.length() else file.name })
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    private fun resolve(uri: Uri): File? {
        val inbox = context?.getExternalFilesDir(INBOX) ?: return null
        return resolveIn(inbox, uri.lastPathSegment)
    }

    companion object {
        const val INBOX = "debug-import"

        /** The file named [name] directly inside [inbox], or null for a missing file, a path, or anything leading out of the folder. */
        fun resolveIn(inbox: File, name: String?): File? {
            if (name == null || !DebugImporter.isPlainName(name)) return null
            val file = File(inbox, name)
            return file.takeIf { it.isFile && it.canonicalFile.parentFile == inbox.canonicalFile }
        }

        /** The type a file name claims; the app checks the real type from the first bytes anyway. */
        fun mimeTypeOf(name: String): String? = when (name.substringAfterLast('.', "").lowercase()) {
            "pdf" -> "application/pdf"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "heic" -> "image/heic"
            else -> null
        }
    }
}
