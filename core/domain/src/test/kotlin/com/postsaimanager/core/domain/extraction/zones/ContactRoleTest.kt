package com.postsaimanager.core.domain.extraction.zones

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.extraction.v2.ExtractionV2Adapter
import com.postsaimanager.core.domain.extraction.v2.ExtractionV2Pipeline
import com.postsaimanager.core.domain.extraction.v2.ExtractionV2Result
import com.postsaimanager.core.domain.extraction.v2.Letter
import com.postsaimanager.core.domain.extraction.v2.Letters
import com.postsaimanager.core.domain.extraction.v2.PartyRole
import com.postsaimanager.core.model.EntityRole
import com.postsaimanager.core.testing.FakeAiEngine
import com.postsaimanager.core.testing.FakePromptSession
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * The role split, at the source: the answer of the contact question is the CONTACT role (a person at the sender who handles the matter,
 * stored as the sender's contact), and the answer of the care-of question stays CARE_OF (a mailbox on the addressee side, only mentioned).
 * The zone pipeline assigns the role by the question it answered, so a care-of person is never turned into a sender's contact later.
 */
class ContactRoleTest {

    private fun read(letter: Letter, yes: (String) -> Boolean): ExtractionV2Result {
        val session = FakePromptSession().apply {
            scorer = { c -> if (yes(c)) 5.0 else -5.0 }
            responder = { _, _ -> "\"text\"" }
        }
        val interpreter = ZoneScoringInterpreter(FakeAiEngine(), session, contextTokens = 4096, topicsInFirstStage = false)
        return runBlocking { ExtractionV2Pipeline().run(letter.pages, interpreter, 4096) }
    }

    private fun says(value: String, statement: String): (String) -> Boolean = { c -> c.contains("«$value»") && c.contains(statement) }

    @Test
    fun `the contact question's answer is the CONTACT role, stored as the sender's contact in the contact slot`() {
        val contact = ScoringDescriptions.ofRole(QuestionNames.CONTACT)
        // The information block's "Ansprechpartner" at the sender.
        val result = read(Letters.n4, says("Herr T. Muster", contact))
        assertThat(result.parties.contact?.name).isEqualTo("Herr T. Muster")
        assertThat(result.parties.contact?.role).isEqualTo(PartyRole.CONTACT)
        assertThat(result.parties.routingPerson).isNull()
        assertThat(result.parties.careOf).isNull()
        val entity = ExtractionV2Adapter().adapt(result).entities.single { it.name == "Herr T. Muster" }
        assertThat(entity.role).isEqualTo(EntityRole.SENDER_CONTACT)
        assertThat(entity.provenance?.slotKey).isEqualTo("contact")
    }

    @Test
    fun `the care-of question's answer is the CARE_OF role, only mentioned and never a contact at the sender`() {
        val careOf = ScoringDescriptions.ofRole(QuestionNames.CARE_OF)
        val result = read(Letters.n5, says("Familie Beispiel", careOf))
        assertThat(result.parties.careOf?.name).isEqualTo("Familie Beispiel")
        assertThat(result.parties.contact).isNull()
        assertThat(result.parties.routingPerson).isNull()
        val entities = ExtractionV2Adapter().adapt(result).entities
        val entity = entities.single { it.name == "Familie Beispiel" }
        assertThat(entity.role).isEqualTo(EntityRole.MENTIONED)
        assertThat(entity.relation).contains("care of")
        assertThat(entities.none { it.role == EntityRole.SENDER_CONTACT }).isTrue()
    }

    @Test
    fun `the contact at the sender and the person the letter is sent to the attention of keep each their role`() {
        val contact = ScoringDescriptions.ofRole(QuestionNames.CONTACT)
        val careOf = ScoringDescriptions.ofRole(QuestionNames.CARE_OF)
        // N4: "Ansprechpartner: Herr T. Muster" in the information block, and "z. Hd. Frau Erika Mustermann" in the address window.
        val result = read(Letters.n4) { c -> says("Herr T. Muster", contact)(c) || says("Frau Erika Mustermann", careOf)(c) }
        assertThat(result.parties.contact?.name).isEqualTo("Herr T. Muster")
        assertThat(result.parties.careOf?.name).isEqualTo("Frau Erika Mustermann")
        assertThat(result.parties.careOf?.role).isEqualTo(PartyRole.CARE_OF)
        val entities = ExtractionV2Adapter().adapt(result).entities
        assertThat(entities.single { it.name == "Herr T. Muster" }.role).isEqualTo(EntityRole.SENDER_CONTACT)
        // The person at the addressee is only mentioned: never the sender's contact.
        assertThat(entities.single { it.name == "Frau Erika Mustermann" }.role).isEqualTo(EntityRole.MENTIONED)
        assertThat(entities.filter { it.role == EntityRole.SENDER_CONTACT }).hasSize(1)
    }
}
