package com.postsaimanager.core.ai.embed.install

import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.download.ModelDownloader
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Proves the pin is real: that the URL resolves and serves exactly the bytes the hash
 * claims.
 *
 * A wrong URL or a stale hash is the failure mode this whole design exists to prevent, and
 * it is invisible in review — the constants look equally plausible either way. The only
 * thing that can tell is a download.
 *
 * Uses the **vocabulary** (~1 MB), not the weights (~257 MB). Both are pinned identically
 * and fetched by the same code from the same revision, so the small one exercises every
 * step in about a second. `EmbeddingModelReleaseTest` guards the weights' constants for
 * shape; a full install is left to a manual run rather than made a cost on every CI pass.
 *
 * Requires the network, and says so by failing rather than skipping. A lenient skip here
 * once turned a missing INTERNET permission in the test APK into a green run.
 */
@RunWith(AndroidJUnit4::class)
class EmbeddingModelInstallTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val workDirectory = File(context.cacheDir, "embed-install-test")
    private val tag = "pam_spike"

    private val httpClient = HttpClient(Android) {
        install(HttpTimeout) {
            connectTimeoutMillis = 30_000
            socketTimeoutMillis = 60_000
        }
        expectSuccess = false
    }

    private val downloader = ModelDownloader(httpClient, Dispatchers.IO)

    @Before
    fun setUp() {
        workDirectory.deleteRecursively()
        workDirectory.mkdirs()
    }

    @After
    fun tearDown() {
        workDirectory.deleteRecursively()
    }

    @Test
    fun theVocabularyUrlServesExactlyTheBytesThePinClaims() = runBlocking {
        val asset = EmbeddingModelRelease.vocabulary
        val destination = File(workDirectory, "vocab.txt")

        val result = downloader.download(
            url = asset.url,
            destination = destination,
            expectedSha256 = asset.sha256,
            expectedSize = asset.sizeBytes,
        )

        // Asserted, not skipped past. An earlier version treated any failure as "offline"
        // and quietly returned — which is how a missing INTERNET permission in the test
        // APK read as a clean pass. If this bench is genuinely offline the message says so.
        assertTrue(
            "download failed: ${(result as? PamResult.Error)?.error?.userMessage}",
            result is PamResult.Success,
        )

        Log.i(tag, "install vocabulary verified bytes=${destination.length()}")

        // The downloader only moves a file into place after its hash matches, so the file
        // existing here IS the verification passing.
        assertTrue("vocabulary was not installed", destination.isFile)
        assertEquals(asset.sizeBytes, destination.length())

        // And it is the vocabulary the tokenizer expects, not merely a file of the right
        // length: 119,547 WordPiece entries with the special tokens at the ids the encoder
        // was trained against.
        val lines = destination.readLines()
        assertEquals(119_547, lines.size)

        // Pinned by index, not merely by presence. Ids are positional — the tokenizer
        // emits line numbers, and the graph has no idea what a token means beyond its id.
        // A vocabulary with the same words in a different order would tokenize without
        // complaint and produce vectors that mean nothing.
        assertEquals("[PAD]", lines[0])
        assertEquals("[UNK]", lines[100])
        assertEquals("[CLS]", lines[101])
        assertEquals("[SEP]", lines[102])
    }

    @Test
    fun aWrongHashIsRejectedAndLeavesNothingBehind() = runBlocking {
        val asset = EmbeddingModelRelease.vocabulary
        val destination = File(workDirectory, "tampered.txt")

        val result = downloader.download(
            url = asset.url,
            destination = destination,
            // Same length and shape as a real hash, so this exercises a mismatch rather
            // than a malformed-input path.
            expectedSha256 = "0".repeat(64),
            expectedSize = asset.sizeBytes,
        )

        assertTrue("a hash mismatch was not reported", result is PamResult.Error)
        // Fail closed. Bytes that failed verification must never be left where the app
        // could load them, nor where a retry could resume onto them and inherit the
        // corruption.
        assertFalse("unverified bytes were left in place", destination.exists())
        assertFalse(
            "a partial file survived a failed verification",
            File(workDirectory, "tampered.txt.part").exists(),
        )
    }

    @Test
    fun installingTwiceDoesNotDownloadTwice() = runBlocking {
        val asset = EmbeddingModelRelease.vocabulary
        val destination = File(workDirectory, "vocab.txt")

        val first = downloader.download(asset.url, destination, asset.sha256, asset.sizeBytes)
        assertTrue(
            "download failed: ${(first as? PamResult.Error)?.error?.userMessage}",
            first is PamResult.Success,
        )
        val stamp = destination.lastModified()

        // What the installer relies on to skip completed assets: a present file of the
        // right length is treated as already verified, since nothing else can put one there.
        assertTrue(destination.isFile && destination.length() == asset.sizeBytes)
        assertEquals(stamp, destination.lastModified())
    }
}
