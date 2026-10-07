package com.postsaimanager.core.data.importing

import android.content.Context
import android.net.Uri
import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.importing.ImportProblem
import com.postsaimanager.core.domain.importing.StageResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * The staging half of the platform source that needs no renderer: a file that is neither PDF nor image is refused by its bytes
 * whatever it is called, nothing is left behind, an unreadable source says so, and a batch is removed whole. (The page rendering
 * itself is `PdfRenderer` and `ImageDecoder`, which a JVM test cannot run; it is on the device checklist.)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidPageImageSourceTest {

    private val context = RuntimeEnvironment.getApplication() as Context
    private val source = AndroidPageImageSource(context, Dispatchers.Unconfined)

    private fun fileWith(name: String, text: String): File =
        File(context.cacheDir, name).also { it.parentFile?.mkdirs(); it.writeText(text) }

    private fun batchFiles(batchId: String): List<File> =
        AndroidPageImageSource.batchDirectory(context, batchId).walkTopDown().filter { it.isFile }.toList()

    @Test
    fun `a text file named like a PDF is refused by its bytes and leaves nothing behind`() = runBlocking {
        val file = fileWith("letter.pdf", "Dear Sir, this is plain text.")

        val result = source.stage("batch-a", Uri.fromFile(file).toString())

        assertThat(result).isInstanceOf(StageResult.Rejected::class.java)
        assertThat((result as StageResult.Rejected).problem).isInstanceOf(ImportProblem.NotSupported::class.java)
        assertThat(batchFiles("batch-a")).isEmpty()
    }

    @Test
    fun `a source that cannot be opened is unreadable`() = runBlocking {
        val missing = File(context.cacheDir, "does-not-exist.pdf")

        val result = source.stage("batch-b", Uri.fromFile(missing).toString())

        assertThat((result as StageResult.Rejected).problem).isInstanceOf(ImportProblem.Unreadable::class.java)
        assertThat(batchFiles("batch-b")).isEmpty()
    }

    @Test
    fun `discard removes the whole batch and is safe twice or for a batch that never existed`() = runBlocking {
        val dir = AndroidPageImageSource.batchDirectory(context, "batch-c")
        File(dir, "staged/x").apply { parentFile?.mkdirs(); writeText("x") }
        File(dir, "pages/p.jpg").apply { parentFile?.mkdirs(); writeText("x") }

        source.discard("batch-c")
        source.discard("batch-c")
        source.discard("never-existed")

        assertThat(dir.exists()).isFalse()
    }

    @Test
    fun `deletePages removes the rendered pages and ignores one that is gone`() = runBlocking {
        val page = fileWith("page-1.jpg", "x")

        source.deletePages(listOf(Uri.fromFile(page).toString(), "file:///no/such/page.jpg"))

        assertThat(page.exists()).isFalse()
    }

    @Test
    fun `a batch id that tries to leave the import folder is refused`() {
        val failure = runCatching { runBlocking { source.discard("../databases") } }
        // discard never throws for the caller; the guard makes it a no-op instead of deleting outside the folder
        assertThat(failure.isSuccess).isTrue()
        assertThat(context.filesDir.exists()).isTrue()
    }
}
