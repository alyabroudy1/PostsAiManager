package com.postsaimanager.core.data.importing

import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/** Copies a stream while counting and hashing it, and gives up when it is larger than allowed (never reading the rest). */
object BoundedCopy {

    sealed interface Result {
        data class Copied(val bytes: Long, val sha256: String) : Result

        /** The input has more than the allowed bytes; what was written so far is for the caller to delete. */
        data object TooLarge : Result
    }

    fun copy(input: InputStream, output: OutputStream, maxBytes: Long): Result {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_BYTES)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBytes) return Result.TooLarge
            digest.update(buffer, 0, read)
            output.write(buffer, 0, read)
        }
        return Result.Copied(total, digest.digest().joinToString("") { "%02x".format(it) })
    }

    private const val BUFFER_BYTES = 64 * 1024
}
