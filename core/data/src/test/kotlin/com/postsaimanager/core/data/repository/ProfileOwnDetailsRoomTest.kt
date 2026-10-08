package com.postsaimanager.core.data.repository

import android.content.Context
import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.data.database.PamDatabase
import com.postsaimanager.core.model.ContactPerson
import com.postsaimanager.core.model.CustomDetail
import com.postsaimanager.core.model.Profile
import com.postsaimanager.core.model.ProfileKind
import com.postsaimanager.core.model.ProfileSuggestion
import com.postsaimanager.core.model.SuggestionField
import com.postsaimanager.core.model.SuggestionStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The user's own details and the organisation suggestions on the real schema (in-memory Room, foreign keys on). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProfileOwnDetailsRoomTest {

    private lateinit var db: PamDatabase
    private lateinit var profiles: ProfileRepositoryImpl
    private lateinit var contacts: ContactRepositoryImpl
    private lateinit var suggestions: ProfileSuggestionRepositoryImpl

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication() as Context, PamDatabase::class.java)
            .allowMainThreadQueries().build()
        profiles = ProfileRepositoryImpl(db.profileDao(), db.dismissedEntityDao(), db.documentDao(), Dispatchers.Unconfined)
        contacts = ContactRepositoryImpl(db, db.contactDao(), db.dismissedEntityDao(), Dispatchers.Unconfined)
        suggestions = ProfileSuggestionRepositoryImpl(db.profileSuggestionDao(), Dispatchers.Unconfined)
    }

    @After
    fun close() = db.close()

    private fun profile(id: String, name: String, kind: ProfileKind = ProfileKind.ORGANISATION, details: List<CustomDetail> = emptyList()) =
        Profile(id = id, kind = kind, name = name, customDetails = details, createdAt = 1, modifiedAt = 1)

    private suspend fun load(id: String) = (profiles.getProfileById(id) as PamResult.Success).data

    @Test
    fun `a profile's own details round-trip in the user's order and can be edited and removed`(): Unit = runBlocking {
        val details = listOf(CustomDetail("Customer number", "4711"), CustomDetail("Opening hours", "Mon-Fri 8-12"))
        profiles.createProfile(profile("p1", "Maria", ProfileKind.PERSON, details))

        assertThat(load("p1").customDetails).containsExactlyElementsIn(details).inOrder()

        profiles.updateProfile(load("p1").copy(customDetails = listOf(details[1], details[0].copy(value = "4712"))))
        assertThat(load("p1").customDetails).containsExactly(details[1], details[0].copy(value = "4712")).inOrder()

        profiles.updateProfile(load("p1").copy(customDetails = emptyList()))
        assertThat(load("p1").customDetails).isEmpty()
    }

    @Test
    fun `a profile without details reads an empty list`(): Unit = runBlocking {
        profiles.createProfile(profile("p1", "Maria", ProfileKind.PERSON))

        assertThat(load("p1").customDetails).isEmpty()
    }

    @Test
    fun `the profiles search finds a profile by the label or the value of its own details`(): Unit = runBlocking {
        profiles.createProfile(profile("p1", "Maria", ProfileKind.PERSON, listOf(CustomDetail("Steuer-ID", "12 345 678 901"))))
        profiles.createProfile(profile("jc", "Jobcenter Musterstadt", details = listOf(CustomDetail("Kundennummer", "BG-4711"))))
        profiles.createProfile(profile("tax", "Finanzamt"))

        assertThat(profiles.searchProfiles("BG-4711").first().map { it.id }).containsExactly("jc")
        assertThat(profiles.searchProfiles("steuer").first().map { it.id }).containsExactly("p1")
        assertThat(profiles.searchProfiles("Finanz").first().map { it.id }).containsExactly("tax")
        assertThat(profiles.searchProfiles("nothing like it").first()).isEmpty()
    }

    @Test
    fun `the profiles search finds an organisation by its contacts' names and own details`(): Unit = runBlocking {
        profiles.createProfile(profile("jc", "Jobcenter Musterstadt"))
        profiles.createProfile(profile("tax", "Finanzamt"))
        contacts.addContact(
            ContactPerson("c1", "jc", "Frau Nadine Beispiel", firstSeen = 1, lastSeen = 1, customDetails = listOf(CustomDetail("Direktwahl", "030 99 11"))),
        )

        assertThat(profiles.searchProfiles("Nadine").first().map { it.id }).containsExactly("jc")
        assertThat(profiles.searchProfiles("030 99 11").first().map { it.id }).containsExactly("jc")
        assertThat(profiles.searchProfiles("Direktwahl").first().map { it.id }).containsExactly("jc")
    }

    @Test
    fun `a contact's own details round-trip`(): Unit = runBlocking {
        profiles.createProfile(profile("jc", "Jobcenter Musterstadt"))
        val details = listOf(CustomDetail("Direct line", "030 1"), CustomDetail("Room", "2.14"))
        contacts.addContact(ContactPerson("c1", "jc", "Frau Beispiel", firstSeen = 1, lastSeen = 1, customDetails = details))

        assertThat((contacts.getContact("c1") as PamResult.Success).data.customDetails).containsExactlyElementsIn(details).inOrder()
    }

    // ---- suggestions ----

    private fun suggestion(id: String, field: SuggestionField, value: String, at: Long = 1) =
        ProfileSuggestion(id, "jc", field, value, "d1", at)

    @Test
    fun `a suggestion is stored once per field and value and listed oldest first`(): Unit = runBlocking {
        profiles.createProfile(profile("jc", "Jobcenter Musterstadt"))

        suggestions.offer(suggestion("s2", SuggestionField.EMAIL, "info@jc.example", at = 20))
        suggestions.offer(suggestion("s1", SuggestionField.PHONE, "0800 1", at = 10))
        suggestions.offer(suggestion("s3", SuggestionField.PHONE, "0800 1", at = 30))

        assertThat(suggestions.observePending("jc").first().map { it.id }).containsExactly("s1", "s2").inOrder()
        assertThat(suggestions.all("jc")).hasSize(2)
    }

    @Test
    fun `a dismissed suggestion stays as a row, is no longer pending and blocks the same offer`(): Unit = runBlocking {
        profiles.createProfile(profile("jc", "Jobcenter Musterstadt"))
        suggestions.offer(suggestion("s1", SuggestionField.PHONE, "0800 1"))

        suggestions.dismiss("s1")
        suggestions.offer(suggestion("s2", SuggestionField.PHONE, "0800 1"))

        assertThat(suggestions.observePending("jc").first()).isEmpty()
        assertThat(suggestions.all("jc").single().status).isEqualTo(SuggestionStatus.DISMISSED)
    }

    @Test
    fun `clearing a field removes its pending offers only`(): Unit = runBlocking {
        profiles.createProfile(profile("jc", "Jobcenter Musterstadt"))
        suggestions.offer(suggestion("s1", SuggestionField.PHONE, "0800 1"))
        suggestions.offer(suggestion("s2", SuggestionField.PHONE, "0800 2"))
        suggestions.offer(suggestion("s3", SuggestionField.EMAIL, "info@jc.example"))
        suggestions.dismiss("s2")

        suggestions.clearPending("jc", SuggestionField.PHONE)

        assertThat(suggestions.all("jc").map { it.id }).containsExactly("s2", "s3")
        assertThat(suggestions.get("s3")?.value).isEqualTo("info@jc.example")
        assertThat(suggestions.get("s1")).isNull()
    }

    @Test
    fun `suggestions go with their profile`(): Unit = runBlocking {
        profiles.createProfile(profile("jc", "Jobcenter Musterstadt"))
        suggestions.offer(suggestion("s1", SuggestionField.PHONE, "0800 1"))

        db.profileDao().deleteById("jc")

        assertThat(suggestions.all("jc")).isEmpty()
    }
}
