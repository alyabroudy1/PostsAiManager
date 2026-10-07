package com.postsaimanager.core.domain.memory

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.model.AiMessage
import com.postsaimanager.core.model.DocumentNote
import com.postsaimanager.core.model.ExtractedData
import com.postsaimanager.core.model.ExtractedFieldType
import com.postsaimanager.core.model.MessageRole
import com.postsaimanager.core.model.NoteSource
import com.postsaimanager.core.model.ToolExchange
import com.postsaimanager.core.testing.FakeDocumentNoteRepository
import com.postsaimanager.core.testing.FakeDocumentRepository
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class WriteSessionNotesUseCaseTest {

    /** A generator that answers what it is told, and remembers what it was asked. */
    private class FakeGenerator(var available: Boolean = true, var answer: String? = "NONE", var failure: Exception? = null) : SessionNoteGenerator {
        var asked = 0
        var lastSystem = ""
        var lastPrompt = ""

        override fun isAvailable() = available

        override suspend fun generate(system: String, prompt: String): String? {
            asked++
            lastSystem = system
            lastPrompt = prompt
            failure?.let { throw it }
            return answer
        }
    }

    private val generator = FakeGenerator()
    private val notes = FakeDocumentNoteRepository()
    private val documents = FakeDocumentRepository()
    private val useCase = WriteSessionNotesUseCase(generator, notes, documents, SessionNoteVerifier())

    private fun user(text: String, id: String = "u1") = AiMessage(id, "c", MessageRole.USER, text, createdAt = 1)
    private fun assistant(text: String, tools: List<ToolExchange> = emptyList()) =
        AiMessage("a-${text.hashCode()}", "c", MessageRole.ASSISTANT, text, createdAt = 2, toolTrace = tools)

    private fun write(vararg turns: AiMessage) = runBlocking { useCase("d1", turns.toList()) }

    private fun texts() = notes.snapshot.map { it.text }

    @Test
    fun `the grounded notes of the answer are written as AI notes of the document`() {
        generator.answer = "The user already paid the invoice on 5 Oct.\nThe user will call the Jobcenter."

        val written = write(user("I paid the invoice on 5 Oct, and I will call the Jobcenter."), assistant("Noted."))

        assertThat(written).isEqualTo(2)
        assertThat(texts()).containsExactly("The user already paid the invoice on 5 Oct.", "The user will call the Jobcenter.")
        assertThat(notes.snapshot.map { it.source }.toSet()).containsExactly(NoteSource.AI)
        assertThat(notes.snapshot.map { it.documentId }.toSet()).containsExactly("d1")
        assertThat(notes.snapshot.first().sourceRef).isEqualTo("u1")
    }

    @Test
    fun `NONE writes nothing`() {
        generator.answer = "NONE"

        assertThat(write(user("hello"))).isEqualTo(0)
        assertThat(notes.snapshot).isEmpty()
    }

    @Test
    fun `a number the user did not say is dropped, the rest kept`() {
        generator.answer = "The user paid on 6 Oct.\nThe user plans to call them."

        write(user("I paid on 5 Oct and I plan to call them."))

        assertThat(texts()).containsExactly("The user plans to call them.")
    }

    @Test
    fun `a number from a tool result of the session grounds a note, one in the assistant's own words does not`() {
        generator.answer = "A reminder was set for day 8.\nThe assistant promised 99 euros."
        val tool = ToolExchange("run_intent", "{}", """{"status":"scheduled","day":8}""")

        write(user("remind me"), assistant("I promised 99 euros to you.", listOf(tool)))

        assertThat(texts()).containsExactly("A reminder was set for day 8.")
    }

    @Test
    fun `a repeat of an existing note, or of the document card, is dropped`() {
        notes.seed(DocumentNote("n0", "d1", "The user already paid.", NoteSource.AI, 1, 1))
        documents.seedExtracted(
            "d1",
            ExtractedData(
                id = "f1", documentId = "d1", fieldName = "Sender", fieldValue = "Stadtwerke Beispielstadt",
                fieldType = ExtractedFieldType.OTHER, confidence = 0.9f,
            ),
        )
        generator.answer = "The user already paid!\nStadtwerke Beispielstadt\nThe user wants a payment plan."

        write(user("I already paid. I want a payment plan."))

        assertThat(texts()).containsExactly("The user already paid.", "The user wants a payment plan.")
    }

    @Test
    fun `the cap of three holds`() {
        generator.answer = "fact one\nfact two\nfact three\nfact four"

        assertThat(write(user("a chat"))).isEqualTo(3)
    }

    @Test
    fun `when no model is available the generator is never asked`() {
        generator.available = false
        generator.answer = "a note"

        assertThat(write(user("a chat"))).isEqualTo(0)
        assertThat(generator.asked).isEqualTo(0)
    }

    @Test
    fun `a session with nothing the user said does not wake the model`() {
        assertThat(write(assistant("Hello, how can I help?"))).isEqualTo(0)
        assertThat(write(user("   "))).isEqualTo(0)
        assertThat(generator.asked).isEqualTo(0)
    }

    @Test
    fun `a failed or empty generation writes nothing and never throws`() {
        generator.answer = null
        assertThat(write(user("a chat"))).isEqualTo(0)

        generator.failure = IllegalStateException("engine gone")
        assertThat(write(user("a chat"))).isEqualTo(0)
        assertThat(notes.snapshot).isEmpty()
    }

    @Test
    fun `the question carries the conversation and the notes already kept`() {
        notes.seed(DocumentNote("n0", "d1", "Reminder set for 8 Oct", NoteSource.ACTION, 1, 1))

        write(user("remind me about it"), assistant("Done."))

        assertThat(generator.lastSystem).isEqualTo(SessionNotesFormat.SYSTEM)
        assertThat(generator.lastPrompt).contains("User: remind me about it")
        assertThat(generator.lastPrompt).contains("Reminder set for 8 Oct")
    }

    @Test
    fun `running it twice for the same session adds nothing the second time`() {
        generator.answer = "The user will call the Jobcenter."
        val session = arrayOf(user("I will call the Jobcenter."))

        write(*session)
        write(*session)

        assertThat(notes.snapshot).hasSize(1)
    }
}
