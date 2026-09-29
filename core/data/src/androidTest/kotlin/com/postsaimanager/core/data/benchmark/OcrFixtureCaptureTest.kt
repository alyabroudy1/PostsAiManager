package com.postsaimanager.core.data.benchmark

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import com.postsaimanager.core.data.repository.OcrService
import com.postsaimanager.core.model.OcrBlock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Dev tool: runs the production OCR path ([OcrService], ML Kit with the app's options, the same
 * [OcrBlock] model and JSON encoding the pipeline stores in `document_pages.ocrBlocks`) over page
 * images bundled as test assets, and writes one JSON fixture per document for the JVM extraction
 * benchmark (`core/domain/src/test/.../benchmark`).
 *
 * It never touches the app's database or documents.
 *
 * Images live in `core/data/src/androidTest/assets/benchmark/<key>/page-N.jpg` (git-ignored).
 * Run (only the androidTest APK needs installing):
 * ```
 * adb shell am instrument -w -e class com.postsaimanager.core.data.benchmark.OcrFixtureCaptureTest \
 *     com.postsaimanager.core.data.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 * Output: `<test external files dir>/benchmark/<key>.json`, shape
 * `{key, pages:[{pageNumber, width, height, blocks:[{text, bounds:{left,top,right,bottom}, confidence, language?}]}]}`.
 * Optional `-e keys a,b` limits the run to some keys.
 */
class OcrFixtureCaptureTest {

    private val json = Json { prettyPrint = true; encodeDefaults = false }

    @Test
    fun captureFixtures() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val testContext = instrumentation.context
        val targetContext = instrumentation.targetContext
        val only = InstrumentationRegistry.getArguments().getString("keys")
            ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
        val outDir = File(requireNotNull(testContext.getExternalFilesDir(null)), "benchmark").apply { mkdirs() }
        val ocr = OcrService(targetContext, Dispatchers.IO)
        val keys = (testContext.assets.list("benchmark") ?: emptyArray()).filter { only == null || it in only }.sorted()
        assertTrue("no benchmark assets found", keys.isNotEmpty())

        for (key in keys) {
            val files = (testContext.assets.list("benchmark/$key") ?: emptyArray())
                .filter { it.startsWith("page-") && it.endsWith(".jpg") }
                .sortedBy { it.removePrefix("page-").removeSuffix(".jpg").toInt() }
            val pages = files.mapIndexed { i, name ->
                val local = File(targetContext.cacheDir, "bench-$key-$name")
                testContext.assets.open("benchmark/$key/$name").use { input -> local.outputStream().use { input.copyTo(it) } }
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(local.path, opts)
                val result = ocr.recognizeText(Uri.fromFile(local).toString()).let {
                    (it as? com.postsaimanager.core.common.result.PamResult.Success)?.data
                }
                local.delete()
                val blocks: List<OcrBlock> = result?.blocks.orEmpty()
                JsonObject(
                    mapOf(
                        "pageNumber" to JsonPrimitive(i + 1),
                        "width" to JsonPrimitive(opts.outWidth),
                        "height" to JsonPrimitive(opts.outHeight),
                        "ocrFailed" to JsonPrimitive(result == null),
                        "blocks" to json.parseToJsonElement(
                            json.encodeToString(ListSerializer(OcrBlock.serializer()), blocks),
                        ),
                    ),
                )
            }
            File(outDir, "$key.json").writeText(
                json.encodeToString(
                    JsonObject.serializer(),
                    JsonObject(mapOf("key" to JsonPrimitive(key), "pages" to JsonArray(pages))),
                ),
            )
        }
    }
}
