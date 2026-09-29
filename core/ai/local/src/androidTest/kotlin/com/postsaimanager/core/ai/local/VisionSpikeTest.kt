package com.postsaimanager.core.ai.local

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Debug
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.ai.AiChatMessage
import com.postsaimanager.core.domain.ai.AiChatRole
import com.postsaimanager.core.domain.ai.AiRequest
import com.postsaimanager.core.model.InferenceConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Phase 1, workstream H: what does image input cost on the phone?
 *
 * Measures image encode + prefill time, the number of image tokens per long-side size, the
 * process memory with and without the projector, and the projector's precision (F16, BF16,
 * Q8_0). Models are staged by hand, never bundled:
 * ```
 * adb push Qwen3.5-0.8B-Q4_K_M.gguf /data/local/tmp/h7/text.gguf
 * adb push mmproj-F16.gguf  /data/local/tmp/h7/mmproj-f16.gguf   (also -bf16, -q8)
 * adb push page-1.jpg       /data/local/tmp/h7/img/page.jpg      (an invented letter page)
 * ```
 * Results go to logcat (tag `h7`) and to `<external files>/h7/spike.txt`.
 */
@RunWith(AndroidJUnit4::class)
class VisionSpikeTest {

    private val dir = File("/data/local/tmp/h7")
    private val tag = "h7"
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val out = StringBuilder()

    private fun say(line: String) {
        Log.i(tag, line)
        out.appendLine(line)
    }

    private fun memory(label: String) {
        val status = File("/proc/self/status").readLines()
        fun kb(key: String) = status.firstOrNull { it.startsWith(key) }?.filter { it.isDigit() }?.toLongOrNull() ?: -1
        val info = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
        say("MEM $label rssMb=${kb("VmRSS") / 1024} hwmMb=${kb("VmHWM") / 1024} pssMb=${info.totalPss / 1024} nativePssMb=${info.nativePss / 1024}")
    }

    /** The page scaled so its long side is [side] px, written as PNG where mtmd can read it. */
    private fun scaled(source: File, side: Int): File {
        val bmp = BitmapFactory.decodeFile(source.absolutePath)
        val k = side.toFloat() / maxOf(bmp.width, bmp.height)
        val scaled = Bitmap.createScaledBitmap(bmp, (bmp.width * k).toInt(), (bmp.height * k).toInt(), true)
        val f = File(context.cacheDir, "page-$side.png")
        FileOutputStream(f).use { scaled.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return f
    }

    @Test
    fun measure() = runBlocking {
        val text = File(dir, "text.gguf")
        val page = File(dir, "img/page.jpg")
        assumeTrue("models/page not staged in ${dir.path}", text.exists() && page.exists())

        val engine = LocalAiEngine(Dispatchers.IO)
        memory("start")
        val loaded = engine.load(
            text.absolutePath,
            InferenceConfig(contextTokens = 6144, threads = InferenceConfig.defaultThreadCount()),
        )
        check(loaded is PamResult.Success) { "load failed: $loaded" }
        memory("after text model")

        val prompt = engine.formatPrompt(
            listOf(
                AiChatMessage(
                    AiChatRole.USER,
                    "${AiRequest.IMAGE_MARKER}\nName the sender, the letter date and the total amount of this letter. Answer in one line.",
                ),
            ),
        )

        val projectors = (InstrumentationRegistry.getArguments().getString("projectors") ?: "f16,q8,bf16").split(",")
        val sides = (InstrumentationRegistry.getArguments().getString("sides") ?: "512,768,1024").split(",").map { it.toInt() }
        for (name in projectors) {
            val proj = File(dir, "mmproj-$name.gguf")
            if (!proj.exists()) continue
            val t0 = System.nanoTime()
            val res = engine.loadVision(proj.absolutePath)
            check(res is PamResult.Success) { "loadVision failed: $res" }
            say("PROJECTOR $name sizeMb=${proj.length() / 1_000_000} loadMs=${(System.nanoTime() - t0) / 1_000_000}")
            memory("after mmproj $name")
            for (side in sides) {
                val img = scaled(page, side)
                for (run in 1..2) {
                    val start = System.nanoTime()
                    var first = 0L
                    var tokens = 0
                    val sb = StringBuilder()
                    engine.generate(
                        AiRequest(
                            prompt = prompt,
                            maxTokens = 40,
                            temperature = 0f,
                            imagePaths = listOf(img.absolutePath),
                        ),
                    ).collect {
                        if (first == 0L) first = System.nanoTime()
                        tokens++
                        sb.append(it)
                    }
                    val end = System.nanoTime()
                    val stats = engine.lastVisionStats()
                    say(
                        "RUN proj=$name side=$side run=$run totalMs=${(end - start) / 1_000_000} " +
                            "ttftMs=${(first - start) / 1_000_000} decodeTokens=$tokens " +
                            "decodeTokPerS=${if (tokens > 1) "%.1f".format((tokens - 1) * 1e9 / (end - first)) else "n/a"} " +
                            "$stats text=${sb.toString().replace('\n', ' ').take(160)}",
                    )
                }
                memory("after $name side=$side")
            }
        }
        val dest = File(context.getExternalFilesDir(null), "h7").apply { mkdirs() }
        File(dest, "spike.txt").writeText(out.toString())
        engine.unload()
    }
}
