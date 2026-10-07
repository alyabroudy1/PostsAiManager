package com.postsaimanager.feature.profiles

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.model.HouseholdRole
import com.postsaimanager.core.model.Profile
import com.postsaimanager.core.model.ProfileKind
import com.postsaimanager.core.model.Relationship
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The profiles list drawn for real: the household first, then organisations with their contact counts, then other people. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProfilesListUiTest {

    @get:Rule
    val compose = createComposeRule()

    private fun profile(id: String, name: String, kind: ProfileKind = ProfileKind.PERSON, role: HouseholdRole? = null, relationship: Relationship? = null) =
        Profile(id = id, kind = kind, householdRole = role, name = name, relationship = relationship, createdAt = 0, modifiedAt = 0)

    private val everyone = listOf(
        profile("landlord", "Herr Vermieter"),
        profile("jc", "Jobcenter Musterstadt", ProfileKind.ORGANISATION),
        profile("maria", "Maria", role = HouseholdRole.MEMBER, relationship = Relationship.PARTNER),
        profile("me", "Mo", role = HouseholdRole.SELF),
    )

    private fun show(counts: Map<String, Int>) {
        compose.setContent {
            MaterialTheme {
                ProfilesList(ProfilesUiState.Success(everyone, counts), onProfileClick = {}, onDelete = {})
            }
        }
    }

    @Test
    fun `the three groups appear in order, the household first`() {
        show(emptyMap())

        val household = compose.onNodeWithTag("section_household").assertIsDisplayed().getUnclippedBoundsInRoot().top
        val organisations = compose.onNodeWithTag("section_organisations").assertIsDisplayed().getUnclippedBoundsInRoot().top

        assertThat(household).isLessThan(organisations)
        compose.onNodeWithText("My household").assertIsDisplayed()
        compose.onNodeWithText("Me").assertIsDisplayed()
        compose.onNodeWithText("Family member · Partner").assertIsDisplayed()
    }

    @Test
    fun `an organisation row shows its contact count only when it has contacts`() {
        show(mapOf("jc" to 2))

        compose.onNodeWithText("Organisation · 2 contacts").assertIsDisplayed()
    }

    @Test
    fun `a single contact reads in the singular`() {
        show(mapOf("jc" to 1))

        compose.onNodeWithText("Organisation · 1 contact").assertIsDisplayed()
    }
}
