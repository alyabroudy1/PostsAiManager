package com.postsaimanager.feature.profiles

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
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
    ) {
        draft = draft.copy(name = name)
        compose.setContent {
            MaterialTheme {
                ProfileDetailContent(
                    state = ProfileDetailUiState(
                        draft = draft, loaded = true, facts = facts, isNew = isNew, selfTaken = selfTaken, selfLocked = selfLocked,
                        contacts = contacts,
                    ),
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
        compose.onNodeWithText("Current contact").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Frau Müller").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Earlier").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Nadine Beispiel").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("No longer responsible").performScrollTo().assertIsDisplayed()
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
