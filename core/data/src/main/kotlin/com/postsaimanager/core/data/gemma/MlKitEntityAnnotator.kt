package com.postsaimanager.core.data.gemma

import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.mlkit.nl.entityextraction.DateTimeEntity
import com.google.mlkit.nl.entityextraction.Entity
import com.google.mlkit.nl.entityextraction.EntityExtraction
import com.google.mlkit.nl.entityextraction.EntityExtractor
import com.google.mlkit.nl.entityextraction.EntityExtractorOptions
import com.postsaimanager.core.domain.extraction.candidates.AmountParser
import com.postsaimanager.core.domain.extraction.gemma.EntityAnnotator
import com.postsaimanager.core.domain.extraction.gemma.EntitySpan
import com.postsaimanager.core.domain.extraction.gemma.EntityType
import kotlinx.coroutines.suspendCancellableCoroutine
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * [EntityAnnotator] over ML Kit Entity Extraction (German, English and Arabic): the typed spans of the letter's lines (dates and times,
 * money, IBAN, phone, e-mail, address), so the reading has a second source of candidates next to the ones code finds by shape.
 *
 * The language models are downloaded on demand by ML Kit (a few megabytes each, once). Until a model is on the phone it is not used, and
 * its download is started in the background: an answer from the models that are there, or null when none is yet, in which case the
 * reading goes on with the shape candidates alone. Only the model is downloaded; no text leaves the phone.
 *
 * The lines are annotated as one text (one line per row), so a span that runs over lines (an address) is cut back to its lines.
 */
@Singleton
class MlKitEntityAnnotator @Inject constructor() : EntityAnnotator {

    // Made when the trial first reads a letter, not when the app starts.
    private val extractors by lazy {
        LANGUAGES.associateWith { language -> EntityExtraction.getClient(EntityExtractorOptions.Builder(language).build()) }
    }

    override suspend fun annotate(lines: List<String>): List<EntitySpan>? {
        if (lines.isEmpty()) return emptyList()
        val ready = extractors.filter { (_, extractor) -> isDownloaded(extractor) }
        // Ask for the missing models for the next letter; this one is read without them.
        extractors.filterKeys { it !in ready.keys }.forEach { (language, extractor) -> requestDownload(language, extractor) }
        if (ready.isEmpty()) return null

        val starts = ArrayList<Int>(lines.size)
        val text = buildString {
            lines.forEach { starts += length; append(it.replace('\n', ' ')).append('\n') }
        }
        val spans = LinkedHashSet<EntitySpan>()
        for ((language, extractor) in ready) {
            val annotations = runCatching { extractor.annotate(text).await() }.onFailure { Log.w(TAG, "annotating with $language failed: ${it.message}") }.getOrNull()
                ?: continue
            for (a in annotations) {
                val first = lineOf(starts, a.start)
                val last = lineOf(starts, (a.end - 1).coerceAtLeast(a.start))
                for (entity in a.entities) {
                    val type = typeOf(entity) ?: continue
                    if (type == EntityType.ADDRESS) {
                        // Context for the reader: every line the address covers.
                        (first..last).forEach { spans += EntitySpan(EntityType.ADDRESS, it, lines[it]) }
                    } else if (first == last) {
                        toSpan(type, entity, first, a.annotatedText)?.let { spans += it }
                    }
                }
            }
        }
        return spans.toList()
    }

    private fun toSpan(type: EntityType, entity: Entity, line: Int, printed: String): EntitySpan? = when (type) {
        EntityType.DATE_TIME -> entity.asDateTimeEntity()?.let { dateTime ->
            // A year, a month or a week is no date a letter can name; a day or finer is.
            if (dateTime.dateTimeGranularity < DateTimeEntity.GRANULARITY_DAY) return null
            val at = Instant.ofEpochMilli(dateTime.timestampMillis).atZone(ZoneId.systemDefault())
            EntitySpan(
                type, line, printed, date = at.toLocalDate(),
                time = at.toLocalTime().takeIf { dateTime.dateTimeGranularity > DateTimeEntity.GRANULARITY_DAY },
            )
        }
        EntityType.MONEY -> entity.asMoneyEntity()?.let { money ->
            val cents = money.integerPart.toLong() * CENTS + money.fractionalPart.toLong().coerceIn(0, CENTS - 1)
            EntitySpan(type, line, printed, cents = cents, currency = AmountParser.currencyOf(money.unnormalizedCurrency) ?: money.unnormalizedCurrency.ifBlank { null })
        }
        EntityType.IBAN, EntityType.PHONE, EntityType.EMAIL -> EntitySpan(type, line, printed)
        EntityType.ADDRESS -> null
    }

    private fun typeOf(entity: Entity): EntityType? = when (entity.type) {
        Entity.TYPE_DATE_TIME -> EntityType.DATE_TIME
        Entity.TYPE_MONEY -> EntityType.MONEY
        Entity.TYPE_IBAN -> EntityType.IBAN
        Entity.TYPE_PHONE -> EntityType.PHONE
        Entity.TYPE_EMAIL -> EntityType.EMAIL
        Entity.TYPE_ADDRESS -> EntityType.ADDRESS
        else -> null
    }

    /** The line the character at [offset] is on. */
    private fun lineOf(starts: List<Int>, offset: Int): Int {
        val at = starts.binarySearch(offset)
        return if (at >= 0) at else (-at - 2).coerceAtLeast(0)
    }

    private suspend fun isDownloaded(extractor: EntityExtractor): Boolean = runCatching { extractor.isModelDownloaded().await() }.getOrDefault(false)

    private fun requestDownload(language: String, extractor: EntityExtractor) {
        runCatching {
            extractor.downloadModelIfNeeded()
                .addOnSuccessListener { Log.i(TAG, "entity model $language downloaded") }
                .addOnFailureListener { Log.w(TAG, "entity model $language not downloaded: ${it.message}") }
        }
    }

    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { continuation.resume(it) }
        addOnFailureListener { continuation.resumeWithException(it) }
    }

    private companion object {
        const val TAG = "MlKitEntities"
        const val CENTS = 100L

        /** German, English and Arabic: the v1 languages. Another language is another entry. */
        val LANGUAGES = listOf(EntityExtractorOptions.GERMAN, EntityExtractorOptions.ENGLISH, EntityExtractorOptions.ARABIC)
    }
}
