package com.postsaimanager.core.domain.timeline

import com.postsaimanager.core.domain.document.list.PrintedDateReader
import com.postsaimanager.core.domain.extraction.v2.Canonical
import com.postsaimanager.core.domain.extraction.v2.ExtractionSchema
import com.postsaimanager.core.domain.extraction.v2.ValueMeanings
import com.postsaimanager.core.model.ExtractedData
import java.time.LocalDate
import java.time.ZoneId

/**
 * The day an event happened, per the letter: which of the document's stored dates is it.
 *
 * The model has already decided what each date of the letter means (the stored role `meaning:APPOINTMENT`, `meaning:LETTER_DATE` ...,
 * [ValueMeanings]) and what the letter reports ([EventKind]). This only maps the one onto the other: each kind names, as data
 * ([EventKind.dateMeanings]), the meanings whose date is its day (an appointment: the appointment date; a decision: the first day it
 * applies from when the letter states one; a payment demand: the day of the letter). It reads no word of the letter and decides no
 * meaning.
 *
 * Order: the kind's meanings, best first; then the letter's own date (a date decided as the letter date, or the value of the slot the
 * schema calls the document date); then the day the document was scanned.
 */
object EventDateResolver {

    private const val LETTER_DATE = "LETTER_DATE"

    /**
     * @param fields the document's stored values (a value the user deleted is ignored; a date the user corrected is read as stored)
     * @param scannedAt epoch millis when the document was added, the last resort
     * @return epoch millis of the start of the day in [zone]
     */
    fun resolve(
        kind: EventKind,
        fields: List<ExtractedData>,
        scannedAt: Long,
        zone: ZoneId,
        schema: ExtractionSchema = ExtractionSchema.DEFAULT,
    ): Long {
        val live = fields.filterNot { it.deletedByUser }
        val day = (kind.dateMeanings + LETTER_DATE).firstNotNullOfOrNull { meaning -> dateOf(live, meaning) }
            ?: letterDate(live, schema)
            ?: java.time.Instant.ofEpochMilli(scannedAt).atZone(zone).toLocalDate()
        return day.atStartOfDay(zone).toInstant().toEpochMilli()
    }

    private fun dateOf(fields: List<ExtractedData>, meaningId: String): LocalDate? =
        fields.filter { ValueMeanings.fromRole(it.role)?.id == meaningId }.firstNotNullOfOrNull { PrintedDateReader.read(it.fieldValue) }

    /** The value of a slot the schema calls the document date, for a letter whose reading decided no meaning for it. */
    private fun letterDate(fields: List<ExtractedData>, schema: ExtractionSchema): LocalDate? {
        val keys = schema.allSlots.filter { it.canonical == Canonical.DOCUMENT_DATE }.map { it.json }.toSet()
        return fields.filter { it.slotKey in keys }.firstNotNullOfOrNull { PrintedDateReader.read(it.fieldValue) }
    }
}
