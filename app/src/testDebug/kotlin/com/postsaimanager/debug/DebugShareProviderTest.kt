package com.postsaimanager.debug

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** The debug share provider only ever serves a plain file that sits directly in the debug-import folder. */
class DebugShareProviderTest {

    @TempDir
    lateinit var dir: File

    private fun inbox() = File(dir, "inbox").apply { mkdirs() }

    @Test
    fun `a file in the folder is served, a path or a missing file is not`() {
        val inbox = inbox()
        File(inbox, "letter.pdf").writeText("%PDF-1.4")
        File(dir, "secret.txt").writeText("private")

        assertThat(DebugShareProvider.resolveIn(inbox, "letter.pdf")?.name).isEqualTo("letter.pdf")
        assertThat(DebugShareProvider.resolveIn(inbox, "missing.pdf")).isNull()
        assertThat(DebugShareProvider.resolveIn(inbox, "../secret.txt")).isNull()
        assertThat(DebugShareProvider.resolveIn(inbox, "sub/letter.pdf")).isNull()
        assertThat(DebugShareProvider.resolveIn(inbox, null)).isNull()
    }

    @Test
    fun `a link that leads out of the folder is not served`() {
        val inbox = inbox()
        val outside = File(dir, "secret.txt").apply { writeText("private") }
        java.nio.file.Files.createSymbolicLink(File(inbox, "link.pdf").toPath(), outside.toPath())

        assertThat(DebugShareProvider.resolveIn(inbox, "link.pdf")).isNull()
    }

    @Test
    fun `the type follows the extension`() {
        assertThat(DebugShareProvider.mimeTypeOf("a.PDF")).isEqualTo("application/pdf")
        assertThat(DebugShareProvider.mimeTypeOf("a.jpg")).isEqualTo("image/jpeg")
        assertThat(DebugShareProvider.mimeTypeOf("a.bin")).isNull()
    }
}
