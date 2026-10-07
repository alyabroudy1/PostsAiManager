package com.postsaimanager.core.domain.extraction.zones

import com.postsaimanager.core.domain.extraction.layout.LetterZone
import com.postsaimanager.core.domain.extraction.zones.QuestionNames.ADDRESSEE
import com.postsaimanager.core.domain.extraction.zones.QuestionNames.CARE_OF
import com.postsaimanager.core.domain.extraction.zones.QuestionNames.CONTACT
import com.postsaimanager.core.domain.extraction.zones.QuestionNames.EXTRAS
import com.postsaimanager.core.domain.extraction.zones.QuestionNames.SENDER
import com.postsaimanager.core.domain.extraction.zones.QuestionNames.SUBJECT_PERSON
import com.postsaimanager.core.domain.extraction.zones.QuestionNames.TYPE
import com.postsaimanager.core.domain.extraction.zones.QuestionNames.slot

/**
 * The registry of layout classes. Data only: adding a class is adding one [LayoutTemplate] to [ALL].
 *
 * The hints are English (the letter may be in any language) and say what a block *usually* is. They
 * are priors handed to the model next to the text; nothing decides from them.
 */
object LayoutTemplates {

    // ── hints, shared ────────────────────────────────────────────────────────────
    private const val H_LETTERHEAD =
        "This block is usually the letterhead: the name and contact details of whoever wrote the letter."
    private const val H_RETURN =
        "This small line above the address window usually holds the sender's name and address."
    private const val H_ADDRESS =
        "This block is usually the recipient's address field: the name and address of whoever the letter is addressed to."
    private const val H_INFO =
        "This block usually holds reference data: the date of the letter, reference and customer numbers, a contact person."
    private const val H_SUBJECT = "This line is usually the subject of the letter."
    private const val H_BODY = "This is the text of the letter."
    private const val H_PAYMENT =
        "These lines usually concern amounts, bank details and payment deadlines."
    private const val H_FOOTER =
        "This is the small print at the foot of the page: often the sender's company and bank details, and the page number."

    private val LETTER_DATE = slot("letter_date")
    private val REFERENCE = slot("reference")
    private val CUSTOMER_NO = slot("customer_no")
    private val TOTAL = slot("total")
    private val DUE_DATE = slot("due_date")
    private val IBAN = slot("iban")

    /** The zones of a plain DIN 5008 letter, with the questions each carries. */
    private fun dinZones(returnLine: Boolean, mirrorNote: String = "") = buildList {
        add(ZoneSpec(LetterZone.LETTERHEAD, H_LETTERHEAD, listOf(SENDER)))
        if (returnLine) add(ZoneSpec(LetterZone.RETURN_ADDRESS_LINE, H_RETURN, listOf(SENDER)))
        add(ZoneSpec(LetterZone.ADDRESS_FIELD, H_ADDRESS + mirrorNote, listOf(ADDRESSEE, CARE_OF)))
        add(ZoneSpec(LetterZone.INFO_BLOCK, H_INFO + mirrorNote, listOf(LETTER_DATE, REFERENCE, CUSTOMER_NO, CONTACT)))
        add(ZoneSpec(LetterZone.SUBJECT, H_SUBJECT))
        // References are asked on the reference block and on the body too: many letters print them in a sentence.
        add(ZoneSpec(LetterZone.BODY, H_BODY, listOf(TYPE, SUBJECT_PERSON, REFERENCE, CUSTOMER_NO, TOTAL, DUE_DATE, EXTRAS)))
        add(ZoneSpec(LetterZone.PAYMENT_SECTION, H_PAYMENT, listOf(TOTAL, DUE_DATE, IBAN)))
        add(ZoneSpec(LetterZone.FOOTER, H_FOOTER, listOf(IBAN)))
    }

    private val TOP = Region(0f, 0f, 1f, 0.14f)
    private val INFO_RIGHT = Region(0.45f, 0.08f, 1f, 0.5f)
    private val FOOT = Region(0f, 0.82f, 1f, 1f)
    private val PORTRAIT = Range(0.2f, 1.0f, softness = 0.3f)

    /** DIN 5008 form A: the address field starts high (27 mm), the return line is inside the window. */
    val DIN5008_A = LayoutTemplate(
        id = "DIN5008_A",
        description = "a German business letter in DIN 5008 form A: letterhead on top, the address field below it on the left, reference block on the right",
        signature = TemplateSignature(
            zones = listOf(
                ZoneExpectation(LetterZone.ADDRESS_FIELD, Region(0f, 0.07f, 0.5f, 0.15f), 3f),
                ZoneExpectation(LetterZone.LETTERHEAD, TOP),
                ZoneExpectation(LetterZone.INFO_BLOCK, INFO_RIGHT),
                ZoneExpectation(LetterZone.FOOTER, FOOT, 0.5f),
            ),
            fontRatio = PORTRAIT,
            tableRows = Range(0f, 8f, softness = 4f, weight = 2f),
            rightToLeft = false,
        ),
        zones = dinZones(returnLine = false),
        locale = LocaleHint(countries = setOf("DE", "AT", "CH")),
    )

    /** DIN 5008 form B: the address field starts lower (45 mm), with the return line above it. */
    val DIN5008_B = LayoutTemplate(
        id = "DIN5008_B",
        description = "a German business letter in DIN 5008 form B: letterhead on top, a small return-address line, the address field on the left, reference block on the right",
        signature = TemplateSignature(
            zones = listOf(
                ZoneExpectation(LetterZone.ADDRESS_FIELD, Region(0f, 0.15f, 0.5f, 0.28f), 3f),
                ZoneExpectation(LetterZone.RETURN_ADDRESS_LINE, Region(0f, 0.12f, 0.5f, 0.2f), 1.5f),
                ZoneExpectation(LetterZone.LETTERHEAD, TOP),
                ZoneExpectation(LetterZone.INFO_BLOCK, INFO_RIGHT),
                ZoneExpectation(LetterZone.FOOTER, FOOT, 0.5f),
            ),
            fontRatio = PORTRAIT,
            tableRows = Range(0f, 8f, softness = 4f, weight = 2f),
            rightToLeft = false,
        ),
        zones = dinZones(returnLine = true),
        locale = LocaleHint(countries = setOf("DE", "AT", "CH")),
    )

    /** A letter whose body is mostly a table of positions and amounts (an invoice, a statement). */
    val INVOICE_TABLE = LayoutTemplate(
        id = "INVOICE_TABLE",
        description = "a business letter that is mostly a table of positions and amounts, such as an invoice or a statement of costs",
        signature = TemplateSignature(
            zones = listOf(
                ZoneExpectation(LetterZone.ADDRESS_FIELD, Region(0f, 0.07f, 0.5f, 0.28f), 2f),
                ZoneExpectation(LetterZone.LETTERHEAD, TOP),
            ),
            fontRatio = PORTRAIT,
            tableRows = Range(9f, 1000f, softness = 4f, weight = 4f),
            rightToLeft = false,
        ),
        zones = dinZones(returnLine = true).map {
            if (it.zone == LetterZone.BODY) {
                it.copy(hint = "This is the text of the letter. Rows with several columns are a table of positions: amounts belong to the position on their row.")
            } else {
                it
            }
        },
    )

    /** A narrow receipt roll: shop header, item lines, totals; no addressee. */
    val RECEIPT_NARROW = LayoutTemplate(
        id = "RECEIPT_NARROW",
        description = "a till receipt on a narrow paper roll: the shop's name and address on top, item lines, the total and the payment method below",
        signature = TemplateSignature(
            zones = listOf(ZoneExpectation(LetterZone.LETTERHEAD, Region(0.2f, 0f, 0.8f, 0.35f))),
            fontRatio = Range(1.1f, 6f, softness = 0.5f, weight = 5f),
            absent = mapOf(LetterZone.RETURN_ADDRESS_LINE to 0),
        ),
        zones = listOf(
            ZoneSpec(LetterZone.LETTERHEAD, "This block is usually the shop: its name, address and tax number.", listOf(SENDER)),
            ZoneSpec(
                LetterZone.BODY,
                "These are the item lines, then the total and how it was paid. Each price stands on the row of its item.",
                listOf(TYPE, LETTER_DATE, TOTAL, slot("receipt_no"), REFERENCE, EXTRAS),
            ),
            ZoneSpec(LetterZone.PAYMENT_SECTION, H_PAYMENT, listOf(TOTAL)),
            ZoneSpec(LetterZone.FOOTER, "This is the end of the receipt: often a date, a transaction number and thanks.", listOf(LETTER_DATE, IBAN)),
        ),
        // The analyzer reads a DIN window into what is a list of items: every such zone is just the body here.
        remap = mapOf(
            LetterZone.ADDRESS_FIELD to LetterZone.BODY,
            LetterZone.INFO_BLOCK to LetterZone.BODY,
            LetterZone.SUBJECT to LetterZone.BODY,
            LetterZone.RETURN_ADDRESS_LINE to LetterZone.LETTERHEAD,
        ),
        placements = mapOf(
            "letter_date" to listOf(LetterZone.LETTERHEAD, LetterZone.BODY, LetterZone.FOOTER),
            "total" to listOf(LetterZone.BODY, LetterZone.PAYMENT_SECTION, LetterZone.FOOTER),
        ),
    )

    /** A form or statement of label and value pairs, with no address window. */
    val FORM_KV = LayoutTemplate(
        id = "FORM_KV",
        description = "a form or statement made of label and value pairs in two columns, with no address window",
        signature = TemplateSignature(
            zones = listOf(ZoneExpectation(LetterZone.LETTERHEAD, TOP, 0.5f)),
            fontRatio = PORTRAIT,
            keyValueShare = Range(0.3f, 1f, softness = 0.2f, weight = 4f),
            rightToLeft = false,
            absent = mapOf(LetterZone.ADDRESS_FIELD to 1),
        ),
        zones = listOf(
            ZoneSpec(LetterZone.LETTERHEAD, H_LETTERHEAD, listOf(SENDER, CONTACT)),
            ZoneSpec(
                LetterZone.BODY,
                "These are label and value pairs: a value stands to the right of, or below, its label.",
                listOf(TYPE, ADDRESSEE, SUBJECT_PERSON, LETTER_DATE, REFERENCE, CUSTOMER_NO, TOTAL, DUE_DATE, EXTRAS),
            ),
            ZoneSpec(LetterZone.PAYMENT_SECTION, H_PAYMENT, listOf(TOTAL, DUE_DATE, IBAN)),
            ZoneSpec(LetterZone.FOOTER, H_FOOTER, listOf(IBAN)),
        ),
        remap = mapOf(
            LetterZone.ADDRESS_FIELD to LetterZone.BODY,
            LetterZone.INFO_BLOCK to LetterZone.BODY,
            LetterZone.SUBJECT to LetterZone.BODY,
            LetterZone.RETURN_ADDRESS_LINE to LetterZone.LETTERHEAD,
        ),
    )

    private const val MIRROR = " The page is mirrored (it reads right to left)."

    /** A DIN-like letter mirrored for a right-to-left script: address on the right, reference block on the left. */
    val RTL_DIN = LayoutTemplate(
        id = "RTL_DIN",
        description = "a business letter for a right-to-left script: laid out like a German business letter but mirrored, the address field on the right and the reference block on the left",
        signature = TemplateSignature(
            zones = listOf(
                ZoneExpectation(LetterZone.ADDRESS_FIELD, Region(0.5f, 0.07f, 1f, 0.28f), 3f),
                ZoneExpectation(LetterZone.INFO_BLOCK, Region(0f, 0.08f, 0.55f, 0.5f)),
                ZoneExpectation(LetterZone.LETTERHEAD, TOP),
            ),
            fontRatio = PORTRAIT,
            rightToLeft = true,
        ),
        zones = dinZones(returnLine = true, mirrorNote = MIRROR),
        locale = LocaleHint(scripts = setOf("Arab", "Hebr")),
    )

    /** The zones of a letter with no return line and no reference column: the date block is just the date (and a reference). */
    private val SINGLE_COLUMN_ZONES = dinZones(returnLine = false).map {
        if (it.zone == LetterZone.INFO_BLOCK) {
            it.copy(
                hint = "This block is usually the date of the letter, sometimes with a reference or the contact person.",
                // The contact person is asked here as in every other template: a German letter read as a single-column one (its
                // information block is one line wide) still prints "Ansprechpartner/in" there, and an unasked question finds nobody.
                asks = listOf(LETTER_DATE, REFERENCE, CUSTOMER_NO, CONTACT),
            )
        } else {
            it
        }
    }

    /**
     * A British business letter: the sender's letterhead block in the top right, the date below it, the recipient's
     * address on the left below that, and no return line or reference column beside the address.
     *
     * Regions are soft priors from published conventions, not measurements of a standard: the UK business letter
     * layout of the common style guides (sender's address top right, date under it, recipient's address at the left
     * margin) and the DL window envelope (window about 90 x 35 mm, about 20 mm from the left edge, which puts the
     * address of a letter folded in thirds at roughly 45 to 80 mm of an A4 page's 297 mm, i.e. 0.15 to 0.27).
     * Sources recalled from those conventions, not fetched when this was written.
     */
    val UK_LETTER = LayoutTemplate(
        id = "UK_LETTER",
        description = "a British business letter: the sender's name and address block in the top right, the date below it, the recipient's address on the left below that",
        signature = TemplateSignature(
            zones = listOf(
                ZoneExpectation(LetterZone.LETTERHEAD, Region(0.5f, 0f, 1f, 0.17f), 3f),
                ZoneExpectation(LetterZone.ADDRESS_FIELD, Region(0f, 0.17f, 0.5f, 0.40f), 3f),
            ),
            fontRatio = PORTRAIT,
            rightToLeft = false,
            absent = mapOf(LetterZone.RETURN_ADDRESS_LINE to 0, LetterZone.INFO_BLOCK to 1),
        ),
        zones = SINGLE_COLUMN_ZONES,
        locale = LocaleHint(countries = setOf("GB")),
    )

    /**
     * An American full-block letter: everything flush left. The sender's letterhead top left, the date below it, the
     * inside address on the left, and no right-hand information block.
     *
     * Soft priors from the full block format of the common American business-writing guides (every element at the left
     * margin) and the #10 window envelope (4 1/8 x 9 1/2 in; window 1 1/8 x 4 1/2 in, 7/8 in from the left and 1/2 in
     * from the bottom of the envelope). Folded in thirds, the window shows the inside address at about 2 to 3 in of an
     * 11 in Letter page, i.e. 0.18 to 0.28. Sources recalled from those conventions, not fetched when this was written.
     */
    val US_BLOCK = LayoutTemplate(
        id = "US_BLOCK",
        description = "an American full-block business letter: the sender's letterhead top left, the date below it, the recipient's inside address on the left, everything flush left",
        signature = TemplateSignature(
            zones = listOf(
                ZoneExpectation(LetterZone.LETTERHEAD, Region(0f, 0f, 0.6f, 0.17f), 3f),
                ZoneExpectation(LetterZone.ADDRESS_FIELD, Region(0f, 0.17f, 0.55f, 0.36f), 3f),
            ),
            fontRatio = PORTRAIT,
            rightToLeft = false,
            absent = mapOf(LetterZone.RETURN_ADDRESS_LINE to 0, LetterZone.INFO_BLOCK to 1),
        ),
        zones = SINGLE_COLUMN_ZONES,
        locale = LocaleHint(countries = setOf("US")),
    )

    /** No layout recognised: the whole letter is read as one text. */
    val GENERIC = LayoutTemplate(
        id = "GENERIC",
        description = "a document whose layout was not recognised",
        signature = TemplateSignature(),
        zones = listOf(
            ZoneSpec(
                LetterZone.BODY,
                "No layout was recognised: this is the text of the document in reading order.",
                listOf(TYPE, SENDER, ADDRESSEE, CARE_OF, SUBJECT_PERSON, CONTACT, LETTER_DATE, REFERENCE, CUSTOMER_NO, TOTAL, DUE_DATE, IBAN, EXTRAS),
            ),
        ),
        remap = LetterZone.entries.filter { it != LetterZone.BODY }.associateWith { LetterZone.BODY },
    )

    /** Every matchable class, most specific first; [GENERIC] is the fallback and is not scored. */
    val ALL: List<LayoutTemplate> = listOf(DIN5008_A, DIN5008_B, INVOICE_TABLE, RECEIPT_NARROW, FORM_KV, RTL_DIN, UK_LETTER, US_BLOCK)

    fun byId(id: String): LayoutTemplate? = (ALL + GENERIC).firstOrNull { it.id == id }
}
