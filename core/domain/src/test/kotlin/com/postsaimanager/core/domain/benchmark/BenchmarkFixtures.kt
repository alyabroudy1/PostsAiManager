package com.postsaimanager.core.domain.benchmark

import com.postsaimanager.core.model.OcrBlock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/** One recognised page as stored by the pipeline (`document_pages.ocrBlocks`). */
data class FixturePage(val pageNumber: Int, val width: Int, val height: Int, val blocks: List<OcrBlock>)

/** Real phone OCR of one test letter, captured by `OcrFixtureCaptureTest` (core:data androidTest). */
data class Fixture(val key: String, val pages: List<FixturePage>)

data class ExpectedField(val field: String, val value: String, val page: Int, val note: String?)

/** Ground truth roles of a letter (manifest set 2 and web). */
data class Roles(val sender: String?, val addressees: List<String>)

data class ManifestDoc(
    val key: String,
    val set: String,
    val web: Boolean,
    val expected: List<ExpectedField>,
    val roles: Roles,
    val notFacts: String?,
) {
    /** The manifest names the sender either in `roles` or as an `expected` field. */
    val senderName: String? get() = roles.sender ?: expected.firstOrNull { it.field == "sender" }?.value
}

/**
 * Loads fixtures and manifests.
 *
 * Invented letters ship in `src/test/resources/benchmark/` (fixtures + `manifest-set1/2.json`).
 * Web samples have unclear licences and are never committed: point the system property
 * `benchmark.webDir` (or, because Gradle does not forward -D to test JVMs, the environment variable
 * `BENCHMARK_WEB_DIR`) at a directory holding `W*.json` fixtures and the web `manifest.json` to include
 * them locally. Missing web data is skipped silently.
 */
object BenchmarkFixtures {

    private val json = Json { ignoreUnknownKeys = true }

    class Loaded(val docs: List<Pair<ManifestDoc, Fixture>>, val skipped: List<String>)

    fun load(): Loaded {
        val skipped = mutableListOf<String>()
        val docs = mutableListOf<Pair<ManifestDoc, Fixture>>()
        for (set in listOf("set1", "set2")) {
            val text = resource("/benchmark/manifest-$set.json") ?: run { skipped += "manifest-$set"; null } ?: continue
            for (m in parseManifest(text, set, web = false)) {
                val fx = resource("/benchmark/fixtures/${m.key}.json")?.let { parseFixture(it) }
                if (fx == null) skipped += m.key else docs += m to fx
            }
        }
        val webDir = (System.getProperty("benchmark.webDir") ?: System.getenv("BENCHMARK_WEB_DIR"))?.let(::File)
        if (webDir != null && webDir.isDirectory) {
            val manifest = File(webDir, "manifest.json").takeIf { it.exists() }?.readText()
            if (manifest != null) {
                for (m in parseManifest(manifest, "web", web = true)) {
                    val f = File(webDir, "${m.key}.json")
                    if (f.exists()) docs += m to parseFixture(f.readText()) else skipped += m.key
                }
            }
        }
        return Loaded(docs, skipped)
    }

    private fun resource(path: String): String? =
        BenchmarkFixtures::class.java.getResourceAsStream(path)?.bufferedReader()?.use { it.readText() }

    fun parseFixture(text: String): Fixture {
        val o = json.parseToJsonElement(text).jsonObject
        val pages = o.getValue("pages").jsonArray.map { p ->
            val po = p.jsonObject
            FixturePage(
                pageNumber = po.getValue("pageNumber").jsonPrimitive.content.toInt(),
                width = po["width"]?.jsonPrimitive?.intOrNull ?: 0,
                height = po["height"]?.jsonPrimitive?.intOrNull ?: 0,
                blocks = json.decodeFromJsonElement(ListSerializer(OcrBlock.serializer()), po.getValue("blocks")),
            )
        }
        return Fixture(o.getValue("key").jsonPrimitive.content, pages.sortedBy { it.pageNumber })
    }

    fun parseManifest(text: String, set: String, web: Boolean): List<ManifestDoc> =
        json.parseToJsonElement(text).jsonObject.getValue("documents").jsonArray.map { d ->
            val o = d.jsonObject
            val rolesObj = o["roles"] as? JsonObject
            ManifestDoc(
                key = o.getValue("id").jsonPrimitive.content,
                set = set,
                web = web,
                expected = o.getValue("expected").jsonArray.map { e ->
                    val eo = e.jsonObject
                    ExpectedField(
                        eo.getValue("field").jsonPrimitive.content,
                        eo.getValue("value").jsonPrimitive.content,
                        eo["page"]?.jsonPrimitive?.intOrNull ?: 1,
                        eo["note"]?.jsonPrimitive?.contentOrNull,
                    )
                },
                roles = Roles(
                    sender = rolesObj?.get("sender")?.str(),
                    addressees = (rolesObj?.get("addressees") as? JsonArray)?.mapNotNull { it.str() }.orEmpty(),
                ),
                notFacts = o["not_facts"]?.str(),
            )
        }

    private fun JsonElement.str(): String? = (this as? JsonPrimitive)?.contentOrNull
}
