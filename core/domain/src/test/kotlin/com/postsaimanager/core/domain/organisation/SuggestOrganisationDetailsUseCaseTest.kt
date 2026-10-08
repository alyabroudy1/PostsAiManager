package com.postsaimanager.core.domain.organisation

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.extraction.address.AddressRows
import com.postsaimanager.core.domain.extraction.candidates.CandidateKind
import com.postsaimanager.core.domain.extraction.v2.ExtractionV2Adapter
import com.postsaimanager.core.model.ContactPerson
import com.postsaimanager.core.model.CustomDetail
import com.postsaimanager.core.model.DocumentPage
import com.postsaimanager.core.model.ExtractedData
import com.postsaimanager.core.model.ExtractedFieldType
import com.postsaimanager.core.model.PostalValue
import com.postsaimanager.core.model.ProfileKind
import com.postsaimanager.core.model.ProfileRole
import com.postsaimanager.core.model.ProfileSuggestion
import com.postsaimanager.core.model.ReviewState
import com.postsaimanager.core.model.SuggestionField
import com.postsaimanager.core.model.SuggestionStatus
import com.postsaimanager.core.testing.FakeContactRepository
import com.postsaimanager.core.testing.FakeDetailOwnerQuestion
import com.postsaimanager.core.testing.FakeDocumentRepository
import com.postsaimanager.core.testing.FakeProfileRepository
import com.postsaimanager.core.testing.FakeProfileSuggestionRepository
import com.postsaimanager.core.testing.scoringFollowUps
import com.postsaimanager.core.testing.testDocument
import com.postsaimanager.core.testing.testProfile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * The Jobcenter example: a letter from "Jobcenter Musterstadt" whose letterhead and footer show the office's address, its general
 * phone, e-mail and website, and whose signature shows Frau Nadine Beispiel with her direct number and address. Its profile was created
 * from the sender's name only, so every detail is empty; the letter's details become suggestions, and only for what is empty.
 */
class SuggestOrganisationDetailsUseCaseTest {

    private val documents = FakeDocumentRepository()
    private val profiles = FakeProfileRepository()
    private val contacts = FakeContactRepository()
    private val suggestions = FakeProfileSuggestionRepository()
    private val owner = FakeDetailOwnerQuestion()
    private val suggest = SuggestOrganisationDetailsUseCase(
        documents, profiles, contacts, suggestions, DecideDetailOwnerUseCase(scoringFollowUps(detailOwner = owner)),
    )

    private val letter = """
        Jobcenter Musterstadt
        Beispielweg 12 · 12345 Musterstadt
        Telefon: 0800 555 0199
        E-Mail: info@jobcenter-musterstadt.example
        www.jobcenter-musterstadt.example

        Ihr Antrag vom 3. Mai
        Mit freundlichen Grüßen
        Frau Nadine Beispiel
        Tel. 0800 555 0123
        nadine.beispiel@jobcenter-musterstadt.example
        Bankverbindung: IBAN DE02 1203 0000 0000 2020 51
    """.trimIndent()

    private fun jobcenter() = testProfile(id = "jc", name = "Jobcenter Musterstadt", organization = "Jobcenter Musterstadt", kind = ProfileKind.ORGANISATION)

    private fun row(slot: String, value: String, type: ExtractedFieldType = ExtractedFieldType.ADDRESS, origin: String? = AddressRows.ORIGIN_VERIFIED) =
        ExtractedData(
            id = "f-$slot", documentId = "d1", fieldName = slot, fieldValue = value, fieldType = type, confidence = 0.9f, slotKey = slot, origin = origin,
        )

    private fun found(kind: CandidateKind, n: Int, value: String) = ExtractedData(
        id = "found-$kind-$n", documentId = "d1", fieldName = ExtractionV2Adapter.foundKey(kind, n), fieldValue = value,
        fieldType = ExtractedFieldType.OTHER, confidence = 0.3f, slotKey = ExtractionV2Adapter.foundKey(kind, n),
        origin = ExtractionV2Adapter.FOUND_ORIGIN,
    )

    private suspend fun readTheLetter(
        text: String = letter,
        profile: com.postsaimanager.core.model.Profile = jobcenter(),
        addressOrigin: String? = AddressRows.ORIGIN_VERIFIED,
        storedValues: Boolean = true,
    ) {
        documents.seed(testDocument(id = "d1", title = "Bescheid vom 12. Mai"))
        documents.seedPages("d1", DocumentPage("p1", "d1", 1, "file:///1.jpg", ocrText = text))
        documents.seedExtracted(
            "d1",
            row("sender.street", "Beispielweg", origin = addressOrigin), row("sender.house_number", "12", origin = addressOrigin),
            row("sender.postcode", "12345", origin = addressOrigin), row("sender.city", "Musterstadt", origin = addressOrigin),
            *(
                if (storedValues) {
                    arrayOf(
                        found(CandidateKind.PHONE, 1, "0800 555 0199"), found(CandidateKind.PHONE, 2, "0800 555 0123"),
                        found(CandidateKind.EMAIL, 1, "info@jobcenter-musterstadt.example"),
                        found(CandidateKind.EMAIL, 2, "nadine.beispiel@jobcenter-musterstadt.example"),
                        found(CandidateKind.IBAN, 1, "DE02 1203 0000 0000 2020 51"),
                    )
                } else {
                    emptyArray<ExtractedData>()
                }
                ),
        )
        profiles.seed(profile)
        profiles.linkProfileToDocument("jc", "d1", ProfileRole.SENDER)
    }

    private fun theAiKnows() {
        owner.organisationValues = setOf(
            "0800 555 0199", "info@jobcenter-musterstadt.example", "www.jobcenter-musterstadt.example", "DE02 1203 0000 0000 2020 51",
        )
        owner.contactValues = setOf("0800 555 0123", "nadine.beispiel@jobcenter-musterstadt.example")
    }

    private suspend fun pending() = suggestions.observePending("jc").first()

    private suspend fun linkedContact(): ContactPerson {
        val nadine = ContactPerson("nadine", "jc", "Frau Nadine Beispiel", firstSeen = 1, lastSeen = 1)
        contacts.seed(nadine)
        contacts.linkContactToDocument("nadine", "d1")
        return nadine
    }

    @Test
    fun `the letterhead address, phone, email, website and account become suggestions from this letter`() = runTest {
        readTheLetter()
        theAiKnows()

        val outcome = suggest("d1")

        assertThat(pending().associate { it.field to it.value }).containsExactly(
            SuggestionField.ADDRESS, PostalValue(street = "Beispielweg 12", postalCode = "12345", city = "Musterstadt").encode().trim(),
            SuggestionField.PHONE, "0800 555 0199",
            SuggestionField.EMAIL, "info@jobcenter-musterstadt.example",
            SuggestionField.WEBSITE, "www.jobcenter-musterstadt.example",
            SuggestionField.IBAN, "DE02 1203 0000 0000 2020 51",
        )
        assertThat(pending().map { it.sourceDocumentId }.distinct()).containsExactly("d1")
        assertThat(outcome.offered).isEqualTo(5)
        assertThat(outcome.skipped).isFalse()
    }

    @Test
    fun `a footer's values the reading did not store are found in the letter's own text`() = runTest {
        readTheLetter(storedValues = false)
        theAiKnows()

        suggest("d1")

        assertThat(pending().associate { it.field to it.value }).containsAtLeast(
            SuggestionField.PHONE, "0800 555 0199",
            SuggestionField.EMAIL, "info@jobcenter-musterstadt.example",
            SuggestionField.WEBSITE, "www.jobcenter-musterstadt.example",
            SuggestionField.IBAN, "DE02 1203 0000 0000 2020 51",
        )
    }

    @Test
    fun `the digits of an account, a date and a postcode are not offered as phone numbers`() = runTest {
        readTheLetter(
            text = "Jobcenter Musterstadt\n01067 Dresden\nBescheid vom 03-05-2026\nIBAN DE02 1203 0000 0000 2020 51\nTelefon 0800 555 0199",
            storedValues = false,
        )
        owner.organisationValues = setOf("0800 555 0199", "03-05-2026", "0000 0000 2020", "01067")

        suggest("d1")

        assertThat(pending().filter { it.field == SuggestionField.PHONE }.map { it.value }).containsExactly("0800 555 0199")
        assertThat(owner.asked).containsNoneOf("03-05-2026", "0000 0000 2020", "01067")
    }

    @Test
    fun `the contact person's direct phone and email go to the contact, not to the organisation`() = runTest {
        readTheLetter()
        theAiKnows()
        linkedContact()

        val outcome = suggest("d1")

        val nadine = (contacts.getContact("nadine") as com.postsaimanager.core.common.result.PamResult.Success).data
        assertThat(nadine.phone).isEqualTo("0800 555 0123")
        assertThat(nadine.email).isEqualTo("nadine.beispiel@jobcenter-musterstadt.example")
        assertThat(outcome.contactFilled).isEqualTo(2)
        assertThat(pending().map { it.value }).containsNoneOf("0800 555 0123", "nadine.beispiel@jobcenter-musterstadt.example")
        assertThat(pending().first { it.field == SuggestionField.PHONE }.value).isEqualTo("0800 555 0199")
    }

    @Test
    fun `a contact's own phone is never replaced`() = runTest {
        readTheLetter()
        theAiKnows()
        contacts.seed(ContactPerson("nadine", "jc", "Frau Nadine Beispiel", phone = "030 typed", firstSeen = 1, lastSeen = 1))
        contacts.linkContactToDocument("nadine", "d1")

        suggest("d1")

        val nadine = (contacts.getContact("nadine") as com.postsaimanager.core.common.result.PamResult.Success).data
        assertThat(nadine.phone).isEqualTo("030 typed")
        assertThat(nadine.email).isEqualTo("nadine.beispiel@jobcenter-musterstadt.example")
    }

    @Test
    fun `only empty fields are suggested and the profile itself is never written`() = runTest {
        readTheLetter(profile = jobcenter().copy(phone = "030 typed", street = "Typed Street 1", customDetails = listOf(CustomDetail("iban", "DE00 typed"))))
        theAiKnows()

        suggest("d1")

        assertThat(pending().map { it.field }).containsExactly(SuggestionField.EMAIL, SuggestionField.WEBSITE)
        assertThat(profiles.updated).isEmpty()
        assertThat((profiles.getProfileById("jc") as com.postsaimanager.core.common.result.PamResult.Success).data.phone).isEqualTo("030 typed")
    }

    @Test
    fun `a value that was offered before, accepted or dismissed, is not offered again`() = runTest {
        readTheLetter()
        theAiKnows()
        suggest("d1")
        val phone = pending().first { it.field == SuggestionField.PHONE }
        suggestions.dismiss(phone.id)

        val again = suggest("d1")

        assertThat(again.offered).isEqualTo(0)
        assertThat(pending().map { it.field }).doesNotContain(SuggestionField.PHONE)
        assertThat(suggestions.all("jc").count { it.field == SuggestionField.PHONE }).isEqualTo(1)
        assertThat(suggestions.all("jc").first { it.field == SuggestionField.PHONE }.status).isEqualTo(SuggestionStatus.DISMISSED)
    }

    @Test
    fun `the model decides whose a value is, so a value it does not call the organisation's is not offered`() = runTest {
        readTheLetter()
        owner.organisationValues = setOf("0800 555 0199")

        suggest("d1")

        assertThat(pending().map { it.field }).containsExactly(SuggestionField.ADDRESS, SuggestionField.PHONE)
    }

    @Test
    fun `a value that is not in the letter is never offered`() = runTest {
        readTheLetter(text = "Jobcenter Musterstadt\nBeispielweg 12\nTelefon: 0800 555 0199")
        theAiKnows()

        suggest("d1")

        assertThat(pending().map { it.value }).containsNoneOf("info@jobcenter-musterstadt.example", "DE02 1203 0000 0000 2020 51")
        assertThat(pending().map { it.field }).contains(SuggestionField.PHONE)
    }

    @Test
    fun `without a model the address is still offered and the rest waits for the next reading`() = runTest {
        readTheLetter()
        theAiKnows()
        owner.failing = true

        val outcome = suggest("d1")

        assertThat(outcome.skipped).isTrue()
        assertThat(pending().map { it.field }).containsExactly(SuggestionField.ADDRESS)
    }

    @Test
    fun `an address that did not pass its checks is not offered`() = runTest {
        readTheLetter(addressOrigin = AddressRows.ORIGIN)
        theAiKnows()

        suggest("d1")

        assertThat(pending().map { it.field }).doesNotContain(SuggestionField.ADDRESS)
    }

    @Test
    fun `a letter without a resolved sender organisation offers nothing`() = runTest {
        documents.seed(testDocument(id = "d1"))
        documents.seedPages("d1", DocumentPage("p1", "d1", 1, "file:///1.jpg", ocrText = letter))

        val outcome = suggest("d1")

        assertThat(outcome.skipped).isTrue()
        assertThat(suggestions.rows.value).isEmpty()
    }

    @Test
    fun `an ignored field is not a candidate`() = runTest {
        readTheLetter()
        documents.setFieldReviewState("found-PHONE-1", ReviewState.IGNORED)
        theAiKnows()

        suggest("d1")

        assertThat(pending().map { it.field }).doesNotContain(SuggestionField.PHONE)
    }

    // ---- accept, edit, dismiss ----

    private suspend fun offered(): List<ProfileSuggestion> {
        readTheLetter()
        theAiKnows()
        suggest("d1")
        return pending()
    }

    @Test
    fun `accepting writes the value to the empty field and the other offers for it go`() = runTest {
        val all = offered()
        suggestions.offer(ProfileSuggestion("other", "jc", SuggestionField.PHONE, "0800 000 0000", "d1", 99))
        val accept = AcceptProfileSuggestionUseCase(suggestions, profiles)

        accept(all.first { it.field == SuggestionField.PHONE }.id, now = 5)

        val saved = (profiles.getProfileById("jc") as com.postsaimanager.core.common.result.PamResult.Success).data
        assertThat(saved.phone).isEqualTo("0800 555 0199")
        assertThat(saved.modifiedAt).isEqualTo(5)
        assertThat(pending().map { it.field }).doesNotContain(SuggestionField.PHONE)
    }

    @Test
    fun `accepting an address fills street, postcode and city`() = runTest {
        val all = offered()

        AcceptProfileSuggestionUseCase(suggestions, profiles)(all.first { it.field == SuggestionField.ADDRESS }.id)

        with((profiles.getProfileById("jc") as com.postsaimanager.core.common.result.PamResult.Success).data) {
            assertThat(street).isEqualTo("Beispielweg 12")
            assertThat(postalCode).isEqualTo("12345")
            assertThat(city).isEqualTo("Musterstadt")
        }
    }

    @Test
    fun `accepting with an edited value writes what the user typed`() = runTest {
        val all = offered()

        AcceptProfileSuggestionUseCase(suggestions, profiles)(all.first { it.field == SuggestionField.EMAIL }.id, editedValue = " service@jobcenter-musterstadt.example ")

        assertThat((profiles.getProfileById("jc") as com.postsaimanager.core.common.result.PamResult.Success).data.email)
            .isEqualTo("service@jobcenter-musterstadt.example")
    }

    @Test
    fun `an accepted account is kept as an own detail IBAN`() = runTest {
        val all = offered()

        AcceptProfileSuggestionUseCase(suggestions, profiles)(all.first { it.field == SuggestionField.IBAN }.id)

        assertThat((profiles.getProfileById("jc") as com.postsaimanager.core.common.result.PamResult.Success).data.customDetails)
            .containsExactly(CustomDetail("IBAN", "DE02 1203 0000 0000 2020 51"))
    }

    @Test
    fun `accept all writes every suggested field once and leaves nothing pending`() = runTest {
        val all = offered()

        AcceptProfileSuggestionUseCase(suggestions, profiles).acceptAll("jc", all)

        with((profiles.getProfileById("jc") as com.postsaimanager.core.common.result.PamResult.Success).data) {
            assertThat(street).isEqualTo("Beispielweg 12")
            assertThat(phone).isEqualTo("0800 555 0199")
            assertThat(email).isEqualTo("info@jobcenter-musterstadt.example")
            assertThat(website).isEqualTo("www.jobcenter-musterstadt.example")
            assertThat(customDetails.map { it.label }).containsExactly("IBAN")
        }
        assertThat(pending()).isEmpty()
        assertThat(profiles.updated).hasSize(1)
    }

    @Test
    fun `accepting never overwrites a value the user typed meanwhile`() = runTest {
        val all = offered()
        profiles.updateProfile((profiles.getProfileById("jc") as com.postsaimanager.core.common.result.PamResult.Success).data.copy(phone = "030 typed"))

        AcceptProfileSuggestionUseCase(suggestions, profiles).acceptAll("jc", all)

        assertThat((profiles.getProfileById("jc") as com.postsaimanager.core.common.result.PamResult.Success).data.phone).isEqualTo("030 typed")
        assertThat(SuggestionRules.open(jobcenter().copy(phone = "030 typed"), all).map { it.field }).doesNotContain(SuggestionField.PHONE)
    }

    @Test
    fun `dismissing keeps the value as dismissed so it is not offered again`() = runTest {
        val all = offered()

        DismissProfileSuggestionUseCase(suggestions)(all.first { it.field == SuggestionField.WEBSITE }.id)

        assertThat(pending().map { it.field }).doesNotContain(SuggestionField.WEBSITE)
        assertThat(suggest("d1").offered).isEqualTo(0)
        assertThat((profiles.getProfileById("jc") as com.postsaimanager.core.common.result.PamResult.Success).data.website).isNull()
    }

    @Test
    fun `the page lists each suggestion with the title of its letter`() = runTest {
        offered()

        val shown = ObserveProfileSuggestionsUseCase(suggestions, documents)("jc").first()

        assertThat(shown.map { it.letterTitle }.distinct()).containsExactly("Bescheid vom 12. Mai")
        assertThat(shown).hasSize(5)
    }
}
