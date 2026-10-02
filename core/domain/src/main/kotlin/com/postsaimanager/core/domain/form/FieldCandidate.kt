package com.postsaimanager.core.domain.form

import com.postsaimanager.core.model.FormFieldKind
import com.postsaimanager.core.model.NormBox

/** The shape cue a [FieldCandidate] was found by. Structural cues ([strong]) are passed without a model score. */
enum class FieldEvidence(val strong: Boolean) {
    /** A run of underscores, dots or dashes after or under a label. */
    FILL_RUN(true),

    /** A box glyph (or a look-alike) in front of an option. */
    BOX_GLYPH(true),

    /** A label followed by empty space to the margin or to the next text. */
    LABEL_SPACE(false),

    /** An empty cell under a header cell of a table. */
    TABLE_CELL(false),

    /** A label followed by an empty line under it. */
    LINE_UNDER_LABEL(false),

    /** Short evenly spaced items after a label, with no fill or box glyph: options whose boxes were drawn (OCR cannot see them). */
    OPTION_ROW(false),
}

/**
 * One blank the geometry found, before the model has judged it ([ConfirmFields]) or understood it ([ClassifyFields]).
 *
 * @property labelText the printed label with its trailing colon and fill characters removed; for a lone checkbox the option's text.
 * @property sectionPage the page the [section] heading stands on; differs from [page] when the section carries over from an earlier page
 *   (a multi-page section).
 * @property options the printed options of a [FormFieldKind.CHOICE] group, quote-verified against the page's OCR.
 * @property alreadyFilled the text OCR found inside the fill region: the blank was filled by hand.
 */
data class FieldCandidate(
    val page: Int,
    val labelText: String,
    val labelBox: NormBox?,
    val fillBox: NormBox?,
    val kind: FormFieldKind,
    val evidence: FieldEvidence,
    val section: String? = null,
    val sectionPage: Int? = null,
    val options: List<String> = emptyList(),
    val alreadyFilled: String? = null,
    val orderIndex: Int = 0,
) {
    val strong: Boolean get() = evidence.strong

    /** The section heading comes from an earlier page. */
    val continuesSection: Boolean get() = sectionPage != null && sectionPage != page
}
