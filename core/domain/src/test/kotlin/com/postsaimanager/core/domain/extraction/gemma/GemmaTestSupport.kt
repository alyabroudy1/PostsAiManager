package com.postsaimanager.core.domain.extraction.gemma

import com.postsaimanager.core.domain.extraction.candidates.Candidate
import com.postsaimanager.core.domain.extraction.candidates.CandidateKind
import com.postsaimanager.core.domain.extraction.candidates.CandidateSet
import com.postsaimanager.core.domain.extraction.candidates.Validation
import com.postsaimanager.core.domain.extraction.v2.OfferedCandidates
import com.postsaimanager.core.domain.extraction.v2.OfferedRow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import java.time.LocalDate

/** A reader that answers what a test scripts, and remembers what it was asked. */
internal class ScriptedReader(private val script: (GemmaReaderRequest) -> GemmaReaderOutcome) : GemmaDocumentReader {

    val requests = mutableListOf<GemmaReaderRequest>()

    override suspend fun read(request: GemmaReaderRequest): GemmaReaderOutcome {
        requests += request
        return script(request)
    }

    companion object {
        /** A reader that answers with the JSON [build] makes from the letter it is shown (so ids are always the letter's own). */
        fun answering(build: (GemmaLetter) -> String) = ScriptedReader { r ->
            GemmaReaderOutcome.Answered(build(r.letter), GemmaSchema.build(r.letter), "", ms = 1_234L, usedImage = r.imagePaths.isNotEmpty())
        }

        fun unavailable(reason: String = "no chat model is installed") = ScriptedReader { GemmaReaderOutcome.Unavailable(reason) }
    }
}

internal fun GemmaLetter.idOf(kind: CandidateKind, containing: String): String =
    candidates.first { it.kind == kind && it.raw.contains(containing, ignoreCase = true) }.id

internal fun GemmaLetter.lineOf(containing: String): String = lines.first { it.text.contains(containing, ignoreCase = true) }.id

internal fun obj(vararg pairs: Pair<String, JsonElement>) = JsonObject(pairs.toMap())

internal fun str(s: String) = JsonPrimitive(s)

internal fun arr(vararg items: JsonElement): JsonArray = buildJsonArray { items.forEach { add(it) } }

internal fun party(id: String, kind: String = "person") = obj("id" to str(id), "kind" to str(kind))

internal fun value(id: String, meaning: String) = obj("candidateId" to str(id), "meaning" to str(meaning))

/** A full answer with every field present; [overrides] replace fields of it. */
internal fun answer(overrides: Map<String, JsonElement> = emptyMap()): String {
    val base = mutableMapOf<String, JsonElement>(
        "sender" to party("none"), "addressee" to party("none"), "contact" to party("none"), "subjectPerson" to party("none"),
        "dates" to arr(), "amounts" to arr(), "references" to arr(), "actions" to arr(),
        "category" to str("document"), "language" to str("de"), "name" to str(""), "summary" to str(""), "keyInfo" to arr(),
    )
    base.putAll(overrides)
    return JsonObject(base).toString()
}

/** A small hand-made letter: its lines and the candidates found in them, as the reader sees it and as the verifier looks them up. */
internal class MiniLetter(letterDate: LocalDate? = LocalDate.of(2026, 9, 25)) {

    private fun c(
        id: String, kind: CandidateKind, raw: String, normalized: String, validation: Validation = Validation.Unchecked, attrs: Map<String, String> = emptyMap(),
    ) = Candidate(id, kind, raw, normalized, page = 1, bbox = null, evidence = raw, validation = validation, attrs = attrs)

    val candidates = listOf(
        c("M1", CandidateKind.NAME, "Stadtwerke Beispiel GmbH", "Stadtwerke Beispiel GmbH"),
        c("M2", CandidateKind.NAME, "Erika Mustermann", "Erika Mustermann"),
        c("M3", CandidateKind.NAME, "Ansprechpartner", "Ansprechpartner"),
        c("D1", CandidateKind.DATE, "25.09.2026", "2026-09-25", Validation.Valid),
        c("D2", CandidateKind.DATE, "09.10.2026", "2026-10-09", Validation.Valid),
        c("D3", CandidateKind.DATE, "19.08.2026", "2026-08-19", Validation.Valid),
        c("D4", CandidateKind.DATE, "31.02.2026", "2026-02-31", Validation.Invalid("not a calendar date")),
        c("D5", CandidateKind.DATE, "im Herbst", "autumn"),
        c("A1", CandidateKind.AMOUNT, "64,98 €", "64.98 EUR", attrs = mapOf("cents" to "6498", "currency" to "EUR")),
        c("A2", CandidateKind.AMOUNT, "5,00 €", "5.00 EUR", attrs = mapOf("cents" to "500", "currency" to "EUR")),
        c("A3", CandidateKind.AMOUNT, "??", "??"),
        c("I1", CandidateKind.IBAN, "DE02 1203 0000 0000 2020 51", "DE02120300000000202051", Validation.Valid),
        c("I2", CandidateKind.IBAN, "DE02 1203 0000 0000 2020 52", "DE02120300000000202052", Validation.Invalid("checksum")),
        c("N1", CandidateKind.REFERENCE, "4402917", "4402917", Validation.Valid),
        c("T1", CandidateKind.PHONE, "0800 555 0199", "0800 555 0199", Validation.Valid),
    )

    val offered = OfferedCandidates(candidates.map { OfferedRow(it, emptyList(), listOf(1)) })

    val set = CandidateSet(candidates, letterDate)

    val ocrText = listOf(
        "Stadtwerke Beispiel GmbH", "Erika Mustermann", "Ansprechpartner: Team Forderungen", "Datum: 25.09.2026",
        "Zahlungserinnerung zur Rechnung vom 19.08.2026", "Bitte zahlen Sie 64,98 € bis zum 09.10.2026 auf IBAN DE02 1203 0000 0000 2020 51",
        "Kundennummer: 4402917", "Telefon: 0800 555 0199", "Gebühr 5,00 €",
    ).joinToString("\n")

    val letter = GemmaLetter(
        lines = ocrText.lines().mapIndexed { i, t -> GemmaLine("L${i + 1}", 1, "body", 0.1f, 0.1f * (i + 1), t) },
        candidates = candidates.map { GemmaCandidate(it.id, it.kind, it.raw, it.normalized, "", null) },
    )
}
