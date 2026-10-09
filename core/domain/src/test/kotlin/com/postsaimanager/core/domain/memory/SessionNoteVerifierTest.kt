package com.postsaimanager.core.domain.memory

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class SessionNoteVerifierTest {

    private val verifier = SessionNoteVerifier()
    private val grounding = listOf("I already paid the invoice on 5 October", "I will call them on Monday")
    private val card = "Amount 64,98 EUR\nDue date 15.10.2026\nSender Stadtwerke"

    private fun verify(vararg notes: String, existing: List<String> = emptyList(), ground: List<String> = grounding) =
        verifier.verify(notes.toList(), ground, card, existing)

    @Test
    fun `a note whose numbers are all in the user's words is kept as written`() {
        assertThat(verify("The user paid the invoice on 5 Oct.")).containsExactly("The user paid the invoice on 5 Oct.")
    }

    @Test
    fun `a note that mostly restates an action note is dropped, a different fact about the same bill is kept`() {
        val action = listOf("Reminder set for 9 Oct 09:00: pay the electricity bill")
        val kept = verifier.verify(
            listOf("Reminder set for 9 Oct to pay the electricity bill", "The user paid the invoice on 5 October."),
            grounding + "remind me on 9 Oct",
            card,
            existing = action,
            actionNotes = action,
        )

        assertThat(kept).containsExactly("The user paid the invoice on 5 October.")
    }

    @Test
    fun `a note that restates a request the assistant carried out with a tool is dropped, a stated fact stays`() {
        val kept = verifier.verify(
            listOf("remind me tomorrow at 9", "The user paid the invoice on 5 October."),
            grounding + "remind me tomorrow at 9",
            card,
            existing = emptyList(),
            commands = listOf("remind me tomorrow at 9"),
        )

        assertThat(kept).containsExactly("The user paid the invoice on 5 October.")
    }

    @Test
    fun `the notes prompt tells the model that a request to the assistant is no note`() {
        assertThat(SessionNotesFormat.prompt(emptyList(), emptyList())).contains("request or command the user gave the assistant")
    }

    @Test
    fun `a number the user never said is dropped`() {
        assertThat(verify("The user paid the invoice on 6 Oct.", "The user paid 120 euros.")).isEmpty()
    }

    @Test
    fun `numbers are compared as numbers, leading zeros and Arabic-Indic digits included`() {
        assertThat(verify("Paid on 05 Oct.")).hasSize(1)
        assertThat(verify("تم الدفع في ٥ أكتوبر", ground = listOf("دفعت في 5 أكتوبر"))).hasSize(1)
    }

    @Test
    fun `a number in a tool result of the session grounds a note`() {
        val kept = verifier.verify(listOf("A reminder was set for 8 Oct."), grounding, card, emptyList())
        assertThat(kept).isEmpty()

        val withTool = verifier.verify(listOf("A reminder was set for 8 Oct."), grounding + """{"status":"scheduled","day":8}""", card, emptyList())
        assertThat(withTool).hasSize(1)
    }

    @Test
    fun `a note without numbers needs no number grounding`() {
        assertThat(verify("The user will call the Jobcenter.")).hasSize(1)
    }

    @Test
    fun `a duplicate of an existing note is dropped, whatever the case and punctuation`() {
        assertThat(verify("the user already PAID the invoice on 5 oct", existing = listOf("The user already paid the invoice on 5 Oct."))).isEmpty()
    }

    @Test
    fun `a note that an existing note already contains, or contains, is a duplicate`() {
        assertThat(verify("The user will call on Monday.", existing = listOf("The user will call on Monday, after lunch."))).isEmpty()
    }

    @Test
    fun `a note that only restates the document card is dropped`() {
        assertThat(verify("Due date 15.10.2026", ground = grounding + "15.10.2026")).isEmpty()
    }

    @Test
    fun `two equal notes in one answer keep the first`() {
        assertThat(verify("The user will call them.", "The user will call them!")).hasSize(1)
    }

    @Test
    fun `an empty note and an over-long note are dropped`() {
        assertThat(verify("", "   ", "x".repeat(SessionNotesFormat.MAX_NOTE_CHARS + 1))).isEmpty()
        assertThat(verify("y".repeat(SessionNotesFormat.MAX_NOTE_CHARS))).hasSize(1)
    }

    @Test
    fun `a note that restates a question of the user is no fact, a stated fact next to the question is kept`() {
        val said = listOf("Did I already pay?", "I paid it on 5 October, did it arrive?")
        val kept = verifier.verify(
            listOf("Did I already ask pay", "Has the user paid already?", "The user paid it on 5 October."),
            said, card, emptyList(), userMessages = said,
        )

        assertThat(kept).containsExactly("The user paid it on 5 October.")
    }

    @Test
    fun `a question mark of another script is a question too`() {
        val said = listOf("هل دفعت الفاتورة؟")
        assertThat(verifier.verify(listOf("هل دفعت الفاتورة"), said, card, emptyList(), userMessages = said)).isEmpty()
        assertThat(verifier.verify(listOf("دفع المستخدم الفاتورة"), said, card, emptyList(), userMessages = said)).hasSize(1)
    }

    @Test
    fun `at most three notes are kept`() {
        val kept = verify("one fact", "two facts", "three facts", "four facts", "five facts")

        assertThat(kept).containsExactly("one fact", "two facts", "three facts").inOrder()
    }

    @Test
    fun `a document that already holds the most notes takes no more`() {
        val existing = (1..SessionNoteVerifier.MAX_NOTES_PER_DOCUMENT).map { "existing $it" }

        assertThat(verify("a new fact", existing = existing)).isEmpty()
        assertThat(verify("a new fact", "another new fact", existing = existing.drop(1))).hasSize(1)
    }
}
