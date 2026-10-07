package com.postsaimanager.core.domain.timeline

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.extraction.text.DocumentNameFormat
import com.postsaimanager.core.testing.FakePromptSession
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class EventTitleWriterTest {

    private val letter = "Jobcenter Musterstadt\nBewilligungsbescheid\nIhr Antrag auf Bürgergeld wird ab 01.09.2026 bewilligt."
    private val approval = EventKinds.DEFAULT.byId(EventKinds.APPROVAL)
    private val session = FakePromptSession()

    /** The writer on the letter's open session (the reading opens it with the letter as the prefix). */
    private suspend fun writer(): EventTitleWriter {
        session.open(letter)
        return EventTitleWriter(session)
    }

    @Test
    fun `a title whose names and numbers are in the letter is kept`() = runTest {
        session.responder = { _, _ -> "Bürgergeld bewilligt ab 01.09.2026" }
        assertThat(writer().write("", approval, letter, "de")).isEqualTo("Bürgergeld bewilligt ab 01.09.2026")
    }

    @Test
    fun `a number that is not in the letter drops the title`() = runTest {
        session.responder = { _, _ -> "Bürgergeld bewilligt ab 01.10.2026" }
        assertThat(writer().write("", approval, letter, "de")).isNull()
    }

    @Test
    fun `a name that is not in the letter drops the title`() = runTest {
        session.responder = { _, _ -> "Antrag bei Sozialamt Neustadt bewilligt" }
        assertThat(writer().write("", approval, letter, "de")).isNull()
    }

    @Test
    fun `a title longer than 60 characters or with a line break is dropped`() = runTest {
        session.responder = { _, _ -> "Bürgergeld ".repeat(8) }
        assertThat(writer().write("", approval, letter, "de")).isNull()
        session.responder = { _, _ -> "Bürgergeld\nbewilligt" }
        assertThat(writer().write("", approval, letter, "de")).isNull()
    }

    @Test
    fun `no answer is no title`() = runTest {
        session.responder = { _, _ -> null }
        assertThat(writer().write("", approval, letter, "de")).isNull()
    }

    @Test
    fun `the prompt names what the letter does, the language, the limit and the grounding rule`() = runTest {
        session.responder = { _, _ -> "Bürgergeld bewilligt" }
        writer().write("WHAT WAS READ\n", approval, letter, "de")
        val ask = session.asks.single()
        assertThat(ask.question).contains("This letter ${approval.description}.")
        assertThat(ask.question).contains(EventTitleWriter.MARKER)
        assertThat(ask.question).contains("at most ${DocumentNameFormat.MAX_CHARS} characters")
        assertThat(ask.question).contains("language with the code \"de\"")
        assertThat(ask.question).contains("printed in the document")
        assertThat(ask.grammar).isEqualTo(DocumentNameFormat.grammar())
    }

    @Test
    fun `the prompt of the fallback kind says nothing about what the letter does`() = runTest {
        session.responder = { _, _ -> "Bürgergeld bewilligt" }
        writer().write("", EventKinds.DEFAULT.byId(EventKinds.INFORMATION), letter, null)
        assertThat(session.asks.single().question).doesNotContain("This letter ")
    }
}
