package com.postsaimanager.core.domain.extraction.text

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.extraction.v2.ExtractionV2Pipeline
import com.postsaimanager.core.domain.extraction.v2.Letters
import com.postsaimanager.core.domain.extraction.v2.Oracle
import com.postsaimanager.core.domain.extraction.v2.Prepared
import com.postsaimanager.core.domain.extraction.v2.ScriptedInterpreter
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class SummaryFactsReaderTest {

    private val letter = Letters.invoice

    private val result = runBlocking {
        val oracle = Oracle.structured(letter, Prepared(letter.pages))
        ExtractionV2Pipeline().run(letter.pages, ScriptedInterpreter(oracle.json, Oracle.text(letter)), 4096)
    }

    @Test
    fun `the facts are the verified parties, main values and subject of the result`() {
        val facts = SummaryFactsReader.of(result)
        assertThat(facts.familyId).isEqualTo("invoice_bill")
        assertThat(facts.sender).isEqualTo("Musterfirma GmbH")
        assertThat(facts.addressee).isEqualTo("Erika Mustermann")
        assertThat(facts.amount).isEqualTo("1.284,50 €")
        assertThat(facts.dueDate).isEqualTo("15.10.2026")
        assertThat(facts.date).isEqualTo("28.09.2026")
        assertThat(facts.subject).isEqualTo(result.freeText.subject?.value)
    }

    @Test
    fun `a ticket carries every fact but the subject, and the second stage puts back the subject it verified`() {
        val facts = SummaryFactsReader.of(result)
        val carried = facts.carried()
        assertThat(carried).doesNotContainKey("subject")
        assertThat(carried.keys).containsAtLeast("sender", "addressed_to", "amount", "due_date", "date")
        assertThat(SummaryFacts.of(facts.familyId, carried, facts.subject)).isEqualTo(facts)
    }

    @Test
    fun `a result with no family or parties gives empty facts, not an error`() {
        val bare = runBlocking { ExtractionV2Pipeline().run(letter.pages, null, 4096) }
        val facts = SummaryFactsReader.of(bare)
        assertThat(facts.entries()).isEmpty()
        assertThat(facts.familyId).isEmpty()
    }
}
