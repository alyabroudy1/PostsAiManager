package com.postsaimanager.core.domain.extraction.actions

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.extraction.v2.ExtractionSchema
import org.junit.jupiter.api.Test

/** The catalogue is data, and data is checked: ids are unique, every stored slot a kind names exists, every question is recognisable. */
class ActionKindsTest {

    private val schemaKeys = ExtractionSchema.DEFAULT.allSlots.map { it.json }.toSet()

    @Test
    fun `kind ids are unique and found by id`() {
        assertThat(ActionKinds.ALL.map { it.id }).containsNoDuplicates()
        ActionKinds.ALL.forEach { assertThat(ActionKinds.of(it.id)).isSameInstanceAs(it) }
        assertThat(ActionKinds.of("write_a_poem")).isNull()
    }

    @Test
    fun `the starting catalogue has the eight kinds`() {
        assertThat(ActionKinds.ALL.map { it.id }).containsExactly(
            "pay", "reply", "object_cancel", "attend", "send_documents", "sign_return", "confirm_renew", "contact",
        )
    }

    @Test
    fun `every slot a kind takes a reference or an account from is a slot of the schema`() {
        ActionKinds.ALL.forEach { kind -> assertThat(schemaKeys).containsAtLeastElementsIn(kind.referenceSlots) }
    }

    @Test
    fun `only the payment states an amount, and its party is the sender who is paid`() {
        assertThat(ActionKinds.ALL.filter { ActionPart.AMOUNT in it.parts }.map { it.id }).containsExactly("pay")
        assertThat(ActionKinds.PAY.parts).containsAtLeast(ActionPart.DATE, ActionPart.AMOUNT, ActionPart.PARTY, ActionPart.IBAN)
    }

    @Test
    fun `every question the catalogue makes is recognised as an action question and no other is`() {
        val slot = com.postsaimanager.core.model.TicketSlot("due_date", "Deadline", "15.10.2026")
        val questions = listOf(ActionQuestions.anything()) + ActionKinds.ALL.map(ActionQuestions::kind) +
            ActionKinds.ALL.filter { it.dateMeaning != null }.map { ActionQuestions.date(it, slot) } +
            ActionQuestions.amount(ActionKinds.PAY, com.postsaimanager.core.model.TicketSlot("total", "Amount", "1.284,50 €"))
        questions.forEach { assertThat(ActionQuestions.isActionQuestion(it)).isTrue() }
        // The questions of the reading itself, which mention the reader, are not.
        assertThat(ActionQuestions.isActionQuestion("Does the reader need «Amount: 1 €» to understand this document or to act on it? Answer:")).isFalse()
        assertThat(ActionQuestions.isActionQuestion("Is «1 €» the main amount of this document: what the reader has to pay, or the total? Answer:")).isFalse()
    }

    @Test
    fun `a stored value with a line break is asked on one line`() {
        val q = ActionQuestions.date(ActionKinds.PAY, com.postsaimanager.core.model.TicketSlot("due_date", "Deadline", "15.10.\n2026"))
        assertThat(q).isEqualTo("Is «Deadline: 15.10. 2026» the date by which the reader is asked to pay? Answer:")
    }
}
