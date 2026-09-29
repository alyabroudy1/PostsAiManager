package com.postsaimanager.core.domain.extraction.candidates

import com.postsaimanager.core.model.TextBounds

/**
 * The kinds of facts the deterministic stage can propose.
 *
 * A candidate is *evidence*, not an answer: "this span looks like a date / an amount / an
 * IBAN". Deciding which candidate fills which slot ("the due date", "the sender") is the
 * job of the later selection stage, which may only pick from these ids.
 */
enum class CandidateKind {
    DATE,
    DATETIME,
    RELATIVE_DEADLINE,
    AMOUNT,
    IBAN,
    BIC,
    REFERENCE,
    PERSON_NAME,
    ORG_NAME,
    PHONE,
    EMAIL,
}

/** What sort of identifier a [CandidateKind.REFERENCE] is, as far as its label says. */
enum class ReferenceSubtype {
    INVOICE_NO,
    CUSTOMER_NO,
    CONTRACT_NO,
    POLICY_NO,

    /** Aktenzeichen / Geschäftszeichen. */
    CASE_NO,
    TAX_NO,
    TAX_ID,

    /** Health-insurance or pension-insurance number (KVNR and friends). */
    INSURANCE_NO,
    BEITRAGSNUMMER,
    ACCOUNT_NO,
    METER_NO,
    MATRICULATION_NO,
    RECEIPT_NO,
    OTHER,
}

/**
 * What the nearest label says the value *is*. Covers amounts, dates and relative
 * deadlines; references use [ReferenceSubtype] instead.
 */
enum class LabelKind {
    // amounts
    NET,
    VAT,
    GROSS,
    TOTAL_DUE,
    CREDIT,
    ADVANCE,
    FEE,
    PREMIUM,
    TAX_ASSESSED,
    COST,
    PREVIOUS,
    GENERIC_AMOUNT,

    // dates
    LETTER_DATE,
    INVOICE_DATE,
    DUE_DATE,
    DEADLINE,
    OBJECTION,
    APPOINTMENT,
    EFFECTIVE_FROM,
    CONTRACT_END,
    EVENT_DATE,
    PERIOD,
    REFERENCED_DATE,
    BIRTH_DATE,
}

sealed interface Validation {
    object Valid : Validation {
        override fun toString() = "VALID"
    }

    data class Invalid(val reason: String) : Validation

    object Unchecked : Validation {
        override fun toString() = "UNCHECKED"
    }

    val isValid: Boolean get() = this === Valid
    val isInvalid: Boolean get() = this is Invalid
}

/**
 * Where a block sits in the DIN 5008 letter, when a layout stage knows. Workstream B
 * produces these; the extractor works without them. The zone is a hint carried on a name
 * candidate: names are also found from their shape alone (see [CandidateExtractor]).
 */
enum class BlockZone {
    /** The window-envelope recipient block. */
    ADDRESS_FIELD,

    /** Sender letterhead (top of page 1). */
    LETTERHEAD,

    /** The small single-line sender above the address field (Rücksendeangabe). */
    RETURN_ADDRESS,

    /** Bottom-of-page small print; often names the sender again. */
    FOOTER,
}

/** Identifies one OCR block: 1-based [page], 0-based [index] within that page's block list. */
data class BlockKey(val page: Int, val index: Int)

/**
 * One proposed fact with everything needed to cite it.
 *
 * @property id Stable within one extraction of one document ("D3", "A1", "I2"), so a
 *   grammar can reference candidates by id. Prefix per kind; number in reading order.
 * @property raw The text as printed.
 * @property normalized Canonical form: ISO date "2026-09-28", datetime "2026-11-12T09:30",
 *   amount "1284.50 EUR", compact IBAN, ISO duration "P14D" for relative deadlines, the
 *   reference with collapsed spaces, the stripped name.
 * @property page 1-based page number.
 * @property bbox Bounds of the OCR block the value sits in (null when the input had none).
 * @property evidence The source line the value was read from.
 * @property label Nearest label text ("zahlbar bis", "Gesamtbetrag", "Kundennummer"); empty
 *   when nothing recognisable is near.
 * @property labelKind What that label means, when it is one we know.
 * @property subtype For [CandidateKind.REFERENCE] only.
 * @property attrs Kind-specific extras: amounts carry `cents`, `currency`,
 *   `currencyExplicit`; relative deadlines carry `n`, `unit`, `anchor`; names carry `zone`.
 */
data class Candidate(
    val id: String,
    val kind: CandidateKind,
    val raw: String,
    val normalized: String,
    val page: Int,
    val bbox: TextBounds?,
    val evidence: String,
    val label: String = "",
    val labelKind: LabelKind? = null,
    val subtype: ReferenceSubtype? = null,
    val validation: Validation = Validation.Unchecked,
    val attrs: Map<String, String> = emptyMap(),
) {
    /** Amount in cents, for [CandidateKind.AMOUNT] candidates. */
    val cents: Long? get() = attrs["cents"]?.toLongOrNull()
    val currency: String? get() = attrs["currency"]
}

/** Result of one extraction: the candidates in reading order plus the letter date used to check dates. */
data class CandidateSet(
    val candidates: List<Candidate>,
    val letterDate: java.time.LocalDate?,
) {
    fun ofKind(kind: CandidateKind): List<Candidate> = candidates.filter { it.kind == kind }
    fun byId(id: String): Candidate? = candidates.firstOrNull { it.id == id }
}
