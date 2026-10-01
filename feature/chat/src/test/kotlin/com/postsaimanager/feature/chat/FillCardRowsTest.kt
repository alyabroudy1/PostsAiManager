package com.postsaimanager.feature.chat

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.model.FormField
import com.postsaimanager.core.model.FormFieldKind
import com.postsaimanager.core.model.ReviewState
import org.junit.jupiter.api.Test

class FillCardRowsTest {

    private fun field(
        id: String,
        page: Int = 1,
        order: Int = 0,
        value: String? = null,
        key: String? = null,
        kind: FormFieldKind = FormFieldKind.TEXT,
        reconfirm: Boolean = false,
        skipped: Boolean = false,
        alreadyFilled: String? = null,
        review: ReviewState = ReviewState.UNREVIEWED,
    ) = FormField(
        id = id, formFillId = "f", documentId = "d", page = page, labelText = id, labelBox = null, fillBox = null, kind = kind,
        dataKey = key, value = value, reconfirm = reconfirm, skipped = skipped, alreadyFilled = alreadyFilled, reviewState = review,
        orderIndex = order,
    )

    @Test
    fun `every field gets the status its row shows`() {
        val statuses = FillCardRows.of(
            listOf(
                field("ready", value = "x"),
                field("confirm", value = "y", reconfirm = true),
                field("confirmed", value = "y", reconfirm = true, review = ReviewState.CONFIRMED),
                field("sign", kind = FormFieldKind.SIGNATURE),
                field("hand", skipped = true),
                field("already", alreadyFilled = "by hand"),
                field("open"),
            ),
        ).associate { it.field.id to it.status }

        assertThat(statuses).containsExactly(
            "ready", FillRowStatus.READY,
            "confirm", FillRowStatus.TO_CONFIRM,
            "confirmed", FillRowStatus.READY,
            "sign", FillRowStatus.SIGNATURE,
            "hand", FillRowStatus.BY_HAND,
            "already", FillRowStatus.ALREADY_FILLED,
            "open", FillRowStatus.NEEDS_INPUT,
        )
    }

    @Test
    fun `rows come in page and reading order, sensitive keys are masked by key`() {
        val rows = FillCardRows.of(listOf(field("b", page = 2), field("a", page = 1, order = 1), field("iban", page = 1, order = 0, value = "DE89 3704 0044 0532 0130 00", key = "iban")))

        assertThat(rows.map { it.field.id }).containsExactly("iban", "a", "b").inOrder()
        assertThat(rows.first().sensitive).isTrue()
        assertThat(rows.first().masked).isEqualTo("••••3000")
        assertThat(rows[1].sensitive).isFalse()
        assertThat(rows[1].masked).isNull()
    }

    @Test
    fun `copy all lists the fields that have a value, in order`() {
        val text = FillCardRows.plainText(listOf(field("b", page = 2, value = "2"), field("a", value = "1"), field("empty"))) { it.value.orEmpty() }

        assertThat(text).isEqualTo("a: 1\nb: 2")
    }
}
