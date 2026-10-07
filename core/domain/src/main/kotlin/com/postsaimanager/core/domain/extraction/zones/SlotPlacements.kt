package com.postsaimanager.core.domain.extraction.zones

import com.postsaimanager.core.domain.extraction.layout.LetterZone
import com.postsaimanager.core.domain.extraction.v2.SlotKey
import com.postsaimanager.core.domain.extraction.v2.SlotKind

/**
 * Where the schema's slots are asked: which zones of the page hold the candidates a slot chooses from.
 * Data, like the schema itself. A slot is looked up by its key first, then by its kind, and a
 * [LayoutTemplate] may place a slot differently (its `placements`).
 *
 * A placement is a prior about where a value of that sort usually sits, never a rule about what it is:
 * the model still decides which of the zone's candidates fills the slot, or none.
 */
object SlotPlacements {

    private val HEAD = listOf(LetterZone.INFO_BLOCK, LetterZone.LETTERHEAD)
    private val TEXT = listOf(LetterZone.BODY, LetterZone.PAYMENT_SECTION)
    private val REFS = listOf(LetterZone.INFO_BLOCK, LetterZone.SUBJECT, LetterZone.BODY, LetterZone.PAYMENT_SECTION)

    /** By slot key: the universal core, whose sort of value has a usual home on the page. */
    val BY_KEY: Map<String, List<LetterZone>> = mapOf(
        "letter_date" to HEAD,
        "sent_date" to HEAD,
        "reference" to listOf(LetterZone.INFO_BLOCK, LetterZone.LETTERHEAD),
        "customer_no" to listOf(LetterZone.INFO_BLOCK, LetterZone.LETTERHEAD),
        "total" to TEXT,
        "due_date" to TEXT,
        "iban" to listOf(LetterZone.PAYMENT_SECTION, LetterZone.FOOTER),
    )

    /** By kind, for every slot with no entry of its own. */
    fun byKind(kind: SlotKind): List<LetterZone> = when (kind) {
        SlotKind.AMOUNT, SlotKind.DATE, SlotKind.DEADLINE -> TEXT
        SlotKind.IBAN -> listOf(LetterZone.PAYMENT_SECTION, LetterZone.FOOTER)
        SlotKind.REFERENCE, SlotKind.REFERENCE_LIST -> REFS
        SlotKind.NAME, SlotKind.ACTION -> listOf(LetterZone.BODY)
    }

    /**
     * Where a party's names are offered right after those of its own zones, before the rest of the page: the sender's name is
     * sometimes only printed in the small print at the foot of the page. A prior like the rest (an order, never a gate); the model
     * still decides among the names.
     */
    private val PARTY_FALLBACKS: Map<String, List<LetterZone>> = mapOf(
        QuestionNames.SENDER to listOf(LetterZone.FOOTER),
    )

    fun partyFallback(name: String): List<LetterZone> = PARTY_FALLBACKS[name].orEmpty()

    /**
     * Where a party is usually printed, for a template that places the question in no zone of its own (a receipt has no address field, a
     * form no reference column). Only an order: the question is asked over every name of the page either way (see [ZonePlan.offer]).
     */
    private val PARTY_ZONES: Map<String, List<LetterZone>> = mapOf(
        QuestionNames.SENDER to listOf(LetterZone.LETTERHEAD, LetterZone.RETURN_ADDRESS_LINE),
        QuestionNames.ADDRESSEE to listOf(LetterZone.ADDRESS_FIELD),
        QuestionNames.CARE_OF to listOf(LetterZone.ADDRESS_FIELD),
        QuestionNames.CONTACT to listOf(LetterZone.INFO_BLOCK, LetterZone.LETTERHEAD),
        QuestionNames.SUBJECT_PERSON to listOf(LetterZone.BODY),
    )

    fun partyZones(name: String): List<LetterZone> = PARTY_ZONES[name].orEmpty()

    /** The zones [slot] is asked on under [template], before the template's zone remap. */
    fun zonesFor(slot: SlotKey, template: LayoutTemplate): List<LetterZone> =
        template.placements[slot.json] ?: BY_KEY[slot.json] ?: byKind(slot.kind)
}
