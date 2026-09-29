package com.postsaimanager.core.domain.extraction.v2

/**
 * The prompts for the two model calls. Pure text, no model or grammar details.
 *
 * Written in English on purpose: the letter may be in any language, and the model answers with
 * candidate ids, enums and verbatim quotes, so the answer does not depend on the letter's language.
 * Roles are described by what a party *does* in the letter (who wrote it, who it is addressed to,
 * who handles it, who it is about), never by the words a particular language uses for them. The
 * position tags and the "near" hints are hints, never answers.
 *
 * One short German example teaches the role semantics; the grammar teaches the shape.
 */
object SelectionPrompt {

    /** Below this context window the example is left out to make room for the letter. */
    const val EXAMPLE_MIN_CONTEXT_TOKENS = 3584

    private fun rules(typeIds: String): String = """
        You read one scanned letter and describe it as JSON. The letter can be in any language.

        INPUT. The letter text by page, with position tags in square brackets such as [letterhead],
        [address-field], [info-block], [subject], [body], [payment], [footer]. The tags only hint at
        where a line sits. Then CANDIDATES: values a program found, each with an id, the text as printed,
        a hint of the words next to it, and the page. The hint is not an answer.

        OUTPUT. One JSON object with exactly the keys the grammar allows.
        - type: what kind of document this is ($typeIds). tc: your confidence in it.
        - lang: the language code of the letter.
        - parties: everybody who plays a role (at most ${StructuredGrammar.MAX_PARTIES}). r is SENDER (who wrote and
          sent the letter), ADDRESSEE (who it is addressed to), CO_ADDRESSEE (someone addressed together with
          the addressee), ROUTING (a person named only as the contact or handler at an organisation that is
          the addressee), CARE_OF (a person or household whose address is only used as a mailbox),
          SUBJECT_PERSON (who the letter is about, for example a child when the parents are addressed).
          id is a name candidate id, or the name copied exactly from the letter when there is no candidate.
          A name candidate does not say whether it is a person or an organisation, and a prefix such as a
          routing or "care of" abbreviation is only a hint: you decide k and r. k is PERSON, AUTHORITY, COMPANY
          or OTHER. rel is GUARDIAN_OF when the addressee acts for the subject person (for example the letter is
          addressed to the parents of a child), HOUSEHOLD when a family or household is addressed, otherwise NONE.
          The sender and the addressee are never the same party.
        - s: the value fields. id is the candidate that fills the field and r says what the value is; write
          "NONE" when the letter has no such value. Never write a date, an amount or a number yourself.
          Decide from what the letter says, not from the order of the table. A deadline that is only a period in
          words (for example "within one month", "innerhalb von 14 Tagen") has no candidate: write
          {"rule": the words copied exactly from the letter, "r": ..., "c": ...}.
        - x: up to ${StructuredGrammar.MAX_EXTRAS} other meaningful details that no field covers (a meter number,
          a tariff, a policy holder, a vehicle plate, a school class, a phone number to call ...).
          lb is the label as printed, k a short English snake_case key, id a candidate id or NONE, v the text
          copied exactly from the letter when there is no candidate, otherwise "".
        - c is your confidence for that object: LOW, MEDIUM or HIGH.
    """.trimIndent()

    /** One fictional German letter with the answer the model should give: household addressed, a child as subject. */
    private val EXAMPLE: String = """
        EXAMPLE
        LETTER
        === PAGE 1 ===
        [letterhead] Grundschule Am Waldweg
        [address-field] Familie / Beispiel / Lindenstraße 4 / 12345 Musterstadt
        [info-block] Datum: 02.03.2026
        [body] Der Ausflug am 20.03.2026 kostet 12,00 € pro Kind. Bitte bis 10.03.2026 zurückgeben. Mia Beispiel nimmt teil. Klasse 2a.
        CANDIDATES
        M1: Grundschule Am Waldweg [letterhead] p.1
        M2: Familie Beispiel [address-field] p.1
        D1: 02.03.2026 (near: "Datum") p.1
        D2: 20.03.2026 p.1
        D3: 10.03.2026 p.1
        A1: 12,00 € p.1
        ANSWER
        {"type":"school","tc":"HIGH","lang":"de","parties":[{"r":"SENDER","id":"M1","k":"AUTHORITY","rel":"NONE","c":"HIGH"},{"r":"ADDRESSEE","id":"M2","k":"PERSON","rel":"HOUSEHOLD","c":"HIGH"},{"r":"SUBJECT_PERSON","id":"Mia Beispiel","k":"PERSON","rel":"NONE","c":"MEDIUM"}],"s":{"letter_date":{"id":"D1","r":"LETTER_DATE","c":"HIGH"},"total":{"id":"A1","r":"FEE","c":"HIGH"},"due_date":{"id":"D3","r":"DEADLINE","c":"HIGH"},"iban":"NONE","reference":"NONE","customer_no":"NONE","event_date":{"id":"D2","r":"EVENT","c":"HIGH"}},"x":[{"lb":"Klasse","k":"school_class","id":"NONE","v":"2a","c":"MEDIUM"}]}
    """.trimIndent()

    fun system(schema: ExtractionSchema, withExample: Boolean): String {
        val rules = rules(schema.types.joinToString(", ") { it.id })
        return if (withExample) "$rules\n\n$EXAMPLE" else rules
    }

    /** The candidate table, one candidate per line: `A1: 1.284,50 € (near: "Gesamtbetrag") p.2`. Empty when nothing was found. */
    fun table(offered: OfferedCandidates): String = offered.rows.joinToString("\n") { row ->
        val c = row.candidate
        buildString {
            append(c.id).append(": ").append(c.raw.replace('\n', ' '))
            val near = row.nearLabels.filter { it.isNotBlank() }.distinct()
            if (near.isNotEmpty()) append(" (near: ").append(near.joinToString(" | ") { "\"$it\"" }).append(')')
            c.attrs["zone"]?.let { append(" [").append(zoneTag(it)).append(']') }
            if (c.validation.isInvalid) append(" [failed check]")
            append(" p.").append(row.pages.joinToString("/"))
        }
    }

    private fun zoneTag(zone: String) = when (zone) {
        "ADDRESS_FIELD" -> "address-field"
        "LETTERHEAD" -> "letterhead"
        "RETURN_ADDRESS" -> "return-address-line"
        else -> zone.lowercase()
    }

    /** Call 1's user message: the letter, then the candidate table. */
    fun user(layoutText: String, offered: OfferedCandidates): String = buildString {
        append("LETTER\n")
        append(layoutText)
        append("\n\nCANDIDATES\n")
        append(if (offered.size == 0) "(none)" else table(offered))
    }

    /** Characters the fixed part of call 1's prompt costs, for budgeting the layout text. */
    fun overheadChars(schema: ExtractionSchema, offered: OfferedCandidates, withExample: Boolean): Int =
        system(schema, withExample).length + table(offered).length + 40

    // ── call 2: the free text ─────────────────────────────────────────────────

    val TEXT_SYSTEM: String = """
        You read one scanned letter and write a few short texts about it, as JSON. The letter can be in any
        language; write every text in the letter's own language.
        - other: when the document type is "other", a short name for the kind of document, else "".
        - title: at most 8 words: who wrote it and what it is for.
        - subject: the subject line of the letter, copied exactly as printed.
        - summary: one or two sentences saying what the reader must know or do. Copy sentences from the
          letter where you can.
        - qs: three questions the reader may ask about this letter.
    """.trimIndent()

    /** Call 2's user message: what call 1 decided, then the letter. */
    fun textUser(layoutText: String, documentTypeId: String?): String = buildString {
        if (documentTypeId != null) append("DOCUMENT TYPE: ").append(documentTypeId).append("\n\n")
        append("LETTER\n")
        append(layoutText)
    }

    fun textOverheadChars(): Int = TEXT_SYSTEM.length + 60
}
