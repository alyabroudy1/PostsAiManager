package com.postsaimanager.feature.profiles

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.designsystem.component.TimelineCaseUi
import com.postsaimanager.core.designsystem.component.TimelineEventUi
import com.postsaimanager.core.designsystem.component.TimelineUi
import com.postsaimanager.core.model.CaseStatus
import com.postsaimanager.core.model.EventSource
import com.postsaimanager.core.domain.form.FormDataKeys
import com.postsaimanager.core.model.FactSource
import com.postsaimanager.core.model.Profile
import com.postsaimanager.core.model.ProfileFact
import com.postsaimanager.core.domain.contacts.OrganisationContacts
import com.postsaimanager.core.model.ContactPerson
import com.postsaimanager.core.model.HouseholdRole
import com.postsaimanager.core.model.ProfileKind
import com.postsaimanager.core.model.Relationship
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The profile editor and its "Saved details" section drawn for real (Robolectric, real string resources). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProfileDetailUiTest {

    @get:Rule
    val compose = createComposeRule()

    private val saved = mutableListOf<Pair<String, String>>()
    private val deleted = mutableListOf<String>()
    private val kinds = mutableListOf<ProfileKind>()
    private val roles = mutableListOf<HouseholdRole?>()
    private var draft = profile()

    private fun profile(kind: ProfileKind = ProfileKind.PERSON, role: HouseholdRole? = HouseholdRole.MEMBER) = Profile(
        id = "ahmad", kind = kind, householdRole = role, name = "Ahmad", relationship = Relationship.CHILD.takeIf { role == HouseholdRole.MEMBER },
        createdAt = 0, modifiedAt = 0,
    )

    private fun fact(key: String, value: String, sensitive: Boolean, source: FactSource = FactSource.FORM_ANSWER) =
        ProfileFact("f-$key", "ahmad", key, value, source, sensitive = sensitive, createdAt = 0, updatedAt = 0)

    private fun show(
        facts: List<ProfileFact> = emptyList(),
        isNew: Boolean = false,
        selfTaken: Boolean = false,
        selfLocked: Boolean = false,
        contacts: OrganisationContacts = OrganisationContacts(null, emptyList()),
        name: String = "Ahmad",
        otherOrganisations: List<Profile> = emptyList(),
        contactActions: ContactActions = ContactActions(),
        focusContactId: String? = null,
        timeline: TimelineUi = TimelineUi.EMPTY,
        focusCaseId: String? = null,
    ) {
        draft = draft.copy(name = name)
        compose.setContent {
            MaterialTheme {
                ProfileDetailContent(
                    contactActions = contactActions,
                    state = ProfileDetailUiState(
                        draft = draft, loaded = true, facts = facts, isNew = isNew, selfTaken = selfTaken, selfLocked = selfLocked,
                        contacts = contacts, otherOrganisations = otherOrganisations, focusContactId = focusContactId,
                        timeline = timeline, focusCaseId = focusCaseId,
                    ),
                    onOpenDocument = { openedDocuments += it },
                    onRenameCase = { id, title -> renamedCases += id to title },
                    availableKeys = FormDataKeys.ALL.filter { it.profileColumn == null && it.id !in facts.map { f -> f.key } },
                    snackbarHostState = SnackbarHostState(),
                    onNavigateBack = {},
                    onUpdate = {},
                    onKind = { kinds += it },
                    onRole = { roles += it },
                    onRelationship = {},
                    onSave = {},
                    detailActions = SavedDetailActions(save = { k, v -> saved += k to v }, delete = { deleted += it.key }),
                )
            }
        }
    }

    private val openedDocuments = mutableListOf<String>()
    private val renamedCases = mutableListOf<Pair<String, String>>()

    private val mariasTimeline = TimelineUi(
        cases = listOf(
            TimelineCaseUi(
                caseId = "k1", title = "Bürgergeld", status = CaseStatus.REJECTED, letterCount = 3, organisationName = "Jobcenter",
                personNames = emptyList(),
                events = listOf(
                    TimelineEventUi("e3", "d3", "rejection", 200L * 86_400_000L, "Abgelehnt", EventSource.DOCUMENT),
                    TimelineEventUi("e2", "d2", "approval", 110L * 86_400_000L, "Bewilligt", EventSource.DOCUMENT),
                    TimelineEventUi("e1", "d1", "application_filed", 100L * 86_400_000L, "Antrag", EventSource.DOCUMENT),
                ),
            ),
        ),
    )

    @Test
    fun `a household member's page opens with the timeline and a tap on an event opens its letter`() {
        show(timeline = mariasTimeline, focusCaseId = "k1")

        compose.onNodeWithText("Timeline").assertIsDisplayed()
        compose.onNodeWithText("From Jobcenter").assertIsDisplayed()
        compose.onNodeWithText("3 letters").assertIsDisplayed()
        compose.onNodeWithTag("timeline_event_e2").performClick()

        assertThat(openedDocuments).containsExactly("d2")
    }

    @Test
    fun `a household member without letters sees the empty timeline`() {
        show()
        compose.onNodeWithTag("timeline_empty").assertIsDisplayed()
    }

    @Test
    fun `a new person has no timeline section`() {
        show(isNew = true)
        compose.onNodeWithTag("timeline_section").assertDoesNotExist()
    }

    @Test
    fun `a sensitive detail is masked until tapped, a plain one is shown`() {
        show(facts = listOf(fact("allergies", "Nussallergie", sensitive = true), fact("school", "Grundschule", sensitive = false)))

        compose.onNodeWithText("Allergies and health notes").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Nussallergie").assertDoesNotExist()
        compose.onNodeWithText(MASK).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Grundschule").performScrollTo().assertIsDisplayed()

        compose.onNodeWithText(MASK).performClick()

        compose.onNodeWithText("Nussallergie").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(MASK).assertDoesNotExist()
    }

    @Test
    fun `the source line says where a detail came from`() {
        show(facts = listOf(fact("school", "Grundschule", false, FactSource.FORM_ANSWER)))

        compose.onNodeWithText("from a form", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `adding a detail picks a kind then a value`() {
        show()

        compose.onNodeWithTag("add_detail").performScrollTo().performClick()
        compose.onNodeWithTag("detail_save").assertIsNotEnabled()
        compose.onNodeWithTag("key_school").performClick()
        compose.onNodeWithTag("detail_value").performTextInput("Grundschule A")
        compose.onNodeWithTag("detail_save").performClick()

        assertThat(saved).containsExactly("school" to "Grundschule A")
    }

    @Test
    fun `editing keeps the kind and deleting reports the detail`() {
        show(facts = listOf(fact("school", "Grundschule", false)))

        compose.onNodeWithContentDescription("Edit detail").performScrollTo().performClick()
        compose.onNodeWithTag("detail_value").performTextReplacement("Grundschule B")
        compose.onNodeWithTag("detail_save").performClick()
        compose.onNodeWithContentDescription("Delete detail").performScrollTo().performClick()

        assertThat(saved).containsExactly("school" to "Grundschule B")
        assertThat(deleted).containsExactly("school")
    }

    @Test
    fun `a new person cannot have details yet`() {
        show(isNew = true, name = "")

        compose.onNodeWithText("Save this person first to add details.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("add_detail").assertDoesNotExist()
        compose.onNodeWithTag("save_profile").assertIsNotEnabled()
    }

    @Test
    fun `relationship chips show for a household member only, and Me is disabled when taken`() {
        show(selfTaken = true)

        compose.onNodeWithTag("relationship_CHILD").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("role_SELF").assertIsNotEnabled()
        compose.onNodeWithText("Another profile is already Me.").assertIsDisplayed()
        compose.onNodeWithTag("role_NONE").performClick()
        compose.onNodeWithTag("kind_ORGANISATION").performClick()

        assertThat(roles).containsExactly(null)
        assertThat(kinds).containsExactly(ProfileKind.ORGANISATION)
    }

    @Test
    fun `Me cannot be left from the editor`() {
        draft = profile(role = HouseholdRole.SELF)
        show(selfLocked = true)

        compose.onNodeWithTag("role_NONE").assertIsNotEnabled()
        compose.onNodeWithTag("role_MEMBER").assertIsNotEnabled()
        compose.onNodeWithTag("kind_ORGANISATION").assertIsNotEnabled()
        compose.onNodeWithTag("relationship_CHILD").assertDoesNotExist()
    }

    @Test
    fun `an organisation has no household choice but lists its contacts, current then earlier`() {
        draft = profile(kind = ProfileKind.ORGANISATION, role = null)
        show(
            contacts = OrganisationContacts(
                current = ContactPerson("c2", "ahmad", "Frau Müller", title = "Sachbearbeiterin", phone = "030 123", firstSeen = 1, lastSeen = 2),
                earlier = listOf(ContactPerson("c1", "ahmad", "Nadine Beispiel", firstSeen = 1, lastSeen = 1, active = false)),
            ),
        )

        compose.onNodeWithTag("role_SELF").assertDoesNotExist()
        compose.onNodeWithTag("contacts_section").performScrollTo().assertIsDisplayed()
        // "Current contact" / "Earlier" became "Your contact now" / "Earlier contacts" with the editable section.
        compose.onNodeWithText("Your contact now").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Frau Müller").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Earlier contacts").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Nadine Beispiel").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("No longer responsible").performScrollTo().assertIsDisplayed()
    }

    // ── the contact actions of an organisation page ──

    private val mueller = ContactPerson("c2", "ahmad", "Frau Müller", title = "Sachbearbeiterin", phone = "030 222", email = "mueller@jc.example", firstSeen = 1, lastSeen = 30)
    private val nadine = ContactPerson("c1", "ahmad", "Nadine Beispiel", phone = "030 111", firstSeen = 1, lastSeen = 10)
    private val twoContacts = OrganisationContacts(current = mueller, earlier = listOf(nadine))

    private val savedContacts = mutableListOf<ContactPerson>()
    private val activeChanges = mutableListOf<Pair<String, Boolean>>()
    private val merges = mutableListOf<Pair<String, String>>()
    private val moves = mutableListOf<Pair<String, String>>()
    private val deletes = mutableListOf<String>()
    private val calls = mutableListOf<String>()
    private val emails = mutableListOf<String>()

    private val recorded = ContactActions(
        save = { savedContacts += it },
        setActive = { id, active -> activeChanges += id to active },
        merge = { keep, merged -> merges += keep to merged },
        move = { id, organisation -> moves += id to organisation },
        delete = { deletes += it },
        call = { calls += it },
        email = { emails += it },
    )

    private fun showOrganisation(otherOrganisations: List<Profile> = emptyList(), focus: String? = null) {
        draft = profile(kind = ProfileKind.ORGANISATION, role = null)
        show(contacts = twoContacts, otherOrganisations = otherOrganisations, contactActions = recorded, focusContactId = focus)
    }

    private fun openMenu(contactId: String, item: String) {
        compose.onNodeWithTag("contact_menu_$contactId").performScrollTo().performClick()
        compose.onNodeWithTag(item).performClick()
    }

    @Test
    fun `the current contact can be called and written to, an earlier one cannot`() {
        showOrganisation()

        compose.onNodeWithTag("contact_call").performScrollTo().performClick()
        compose.onNodeWithTag("contact_email").performScrollTo().performClick()

        assertThat(calls).containsExactly("030 222")
        assertThat(emails).containsExactly("mueller@jc.example")
        // Nadine has a phone too, but only the current contact has the buttons.
        compose.onAllNodesWithTag("contact_call").assertCountEquals(1)
    }

    @Test
    fun `editing a contact saves the typed details`() {
        showOrganisation()

        openMenu("c2", "menu_edit")
        compose.onNodeWithTag("contact_field_phone").performTextReplacement("030 999")
        compose.onNodeWithTag("contact_field_title").performTextReplacement("Teamleiterin")
        compose.onNodeWithTag("contact_save").performClick()

        assertThat(savedContacts.single()).isEqualTo(mueller.copy(phone = "030 999", title = "Teamleiterin"))
    }

    @Test
    fun `a contact without a name cannot be saved`() {
        showOrganisation()

        openMenu("c2", "menu_edit")
        compose.onNodeWithTag("contact_field_name").performTextReplacement("")

        compose.onNodeWithTag("contact_save").assertIsNotEnabled()
    }

    @Test
    fun `a contact is marked no longer responsible`() {
        showOrganisation()

        openMenu("c2", "menu_active")

        assertThat(activeChanges).containsExactly("c2" to false)
    }

    @Test
    fun `an inactive contact's menu offers responsible again`() {
        draft = profile(kind = ProfileKind.ORGANISATION, role = null)
        show(
            contacts = OrganisationContacts(current = null, earlier = listOf(nadine.copy(active = false))),
            contactActions = recorded,
        )

        openMenu("c1", "menu_active")

        assertThat(activeChanges).containsExactly("c1" to true)
    }

    @Test
    fun `merging asks which contact stays and sends the picked one as the kept contact`() {
        showOrganisation()

        openMenu("c2", "menu_merge")
        compose.onNodeWithTag("merge_target_c1").performClick()

        // Frau Müller is the same person as Nadine Beispiel: Nadine stays, Müller goes into her.
        assertThat(merges).containsExactly("c1" to "c2")
    }

    @Test
    fun `merging is not offered when the organisation has one contact`() {
        draft = profile(kind = ProfileKind.ORGANISATION, role = null)
        show(contacts = OrganisationContacts(mueller, emptyList()), contactActions = recorded)

        compose.onNodeWithTag("contact_menu_c2").performScrollTo().performClick()

        compose.onNodeWithTag("menu_merge").assertDoesNotExist()
    }

    @Test
    fun `moving a contact lists the other organisations`() {
        val other = Profile(id = "jc2", kind = ProfileKind.ORGANISATION, name = "Jobcenter Nordstadt", createdAt = 0, modifiedAt = 0)
        showOrganisation(otherOrganisations = listOf(other))

        openMenu("c2", "menu_move")
        compose.onNodeWithTag("move_target_jc2").performClick()

        assertThat(moves).containsExactly("c2" to "jc2")
    }

    @Test
    fun `moving is not offered when there is no other organisation`() {
        showOrganisation()

        compose.onNodeWithTag("contact_menu_c2").performScrollTo().performClick()

        compose.onNodeWithTag("menu_move").assertDoesNotExist()
    }

    @Test
    fun `deleting asks first and says the letters stay`() {
        showOrganisation()

        openMenu("c1", "menu_delete")
        compose.onNodeWithText("The letters stay", substring = true).assertIsDisplayed()
        assertThat(deletes).isEmpty()
        compose.onNodeWithTag("contact_delete_confirm").performClick()

        assertThat(deletes).containsExactly("c1")
    }

    @Test
    fun `without a focus the earlier contact is below the fold`() {
        showOrganisation()

        compose.onNodeWithTag("contact_c1").assertIsNotDisplayed()
    }

    @Test
    fun `the contact a letter chip opened the page for is scrolled into view`() {
        showOrganisation(focus = "c1")

        compose.waitForIdle()
        compose.onNodeWithTag("contact_c1").assertIsDisplayed()
    }

    @Test
    fun `an organisation without contacts says so, a person has no contacts section`() {
        draft = profile(kind = ProfileKind.ORGANISATION, role = null)
        show()
        compose.onNodeWithTag("contacts_empty").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `a person has no contacts section`() {
        show()
        compose.onNodeWithTag("contacts_section").assertDoesNotExist()
    }

    @Test
    fun `the sensitive switch explains itself`() {
        show()

        compose.onNodeWithText("Documents of a sensitive person stay out of the all-documents chat.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("sensitive_switch").assertIsDisplayed()
    }
}
