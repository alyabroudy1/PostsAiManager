package com.postsaimanager.core.data.repository

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.common.result.PamError
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.data.database.dao.DismissedEntityDao
import com.postsaimanager.core.data.database.dao.ProfileDao
import com.postsaimanager.core.data.database.dao.ProfileWithRole
import com.postsaimanager.core.data.database.entity.DismissedEntityEntity
import com.postsaimanager.core.data.database.entity.DocumentProfileLinkEntity
import com.postsaimanager.core.data.database.entity.ProfileEntity
import com.postsaimanager.core.model.Profile
import com.postsaimanager.core.model.ProfileType
import com.postsaimanager.core.model.Relationship
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class ProfileRepositoryImplTest {

    private val dao = InMemoryProfileDao()
    private val repository = ProfileRepositoryImpl(
        dao,
        object : DismissedEntityDao {
            override suspend fun dismiss(entity: DismissedEntityEntity) = Unit
            override suspend fun isDismissed(documentId: String, entityName: String) = false
        },
        UnconfinedTestDispatcher(),
    )

    private fun profile(id: String, type: ProfileType, relationship: Relationship? = null) = Profile(
        id = id, type = type, name = id, relationship = relationship, birthDate = "2019-03-12", sensitive = true,
        createdAt = 1, modifiedAt = 1,
    )

    @Test
    fun `relationship, birth date and sensitivity round-trip`() = runTest {
        repository.createProfile(profile("ahmad", ProfileType.FAMILY_MEMBER, Relationship.CHILD))

        val loaded = (repository.getProfileById("ahmad") as PamResult.Success).data

        assertThat(loaded.relationship).isEqualTo(Relationship.CHILD)
        assertThat(loaded.birthDate).isEqualTo("2019-03-12")
        assertThat(loaded.sensitive).isTrue()
        assertThat(loaded.isManaged).isTrue()
    }

    @Test
    fun `a second Me is refused on create and on update, the first can still be updated`() = runTest {
        repository.createProfile(profile("me", ProfileType.USER_SELF))

        val second = repository.createProfile(profile("me2", ProfileType.USER_SELF))
        repository.createProfile(profile("sara", ProfileType.FAMILY_MEMBER))
        val promote = repository.updateProfile(profile("sara", ProfileType.USER_SELF))
        val sameMe = repository.updateProfile(profile("me", ProfileType.USER_SELF).copy(name = "Renamed"))

        assertThat((second as PamResult.Error).error).isInstanceOf(PamError.ValidationError::class.java)
        assertThat(promote).isInstanceOf(PamResult.Error::class.java)
        assertThat(sameMe).isEqualTo(PamResult.Success(Unit))
        assertThat(dao.rows.map { it.id }).containsExactly("me", "sara")
        assertThat(dao.rows.first { it.id == "sara" }.type).isEqualTo("FAMILY_MEMBER")
    }

    @Test
    fun `an unknown stored relationship reads as none`() = runTest {
        dao.insert(entity("x", "PERSON").copy(relationship = "COUSIN_TWICE_REMOVED"))

        assertThat((repository.getProfileById("x") as PamResult.Success).data.relationship).isNull()
    }

    private fun entity(id: String, type: String) = ProfileEntity(
        id = id, type = type, name = id, organization = null, department = null, street = null, city = null,
        postalCode = null, country = null, phone = null, email = null, website = null, reference = null,
        notes = null, completionScore = 0f, missingFields = null, avatarPath = null, createdAt = 1, modifiedAt = 1,
    )
}

private class InMemoryProfileDao : ProfileDao {
    val rows = mutableListOf<ProfileEntity>()

    override fun observeAll(): Flow<List<ProfileEntity>> = MutableStateFlow(rows.toList())
    override fun observeByType(type: String): Flow<List<ProfileEntity>> = MutableStateFlow(rows.filter { it.type == type })
    override fun search(query: String): Flow<List<ProfileEntity>> = emptyFlow()
    override suspend fun getById(id: String): ProfileEntity? = rows.firstOrNull { it.id == id }
    override suspend fun findSimilar(name: String, organization: String?): List<ProfileEntity> = emptyList()
    override suspend fun findOtherSelfId(exceptId: String): String? =
        rows.firstOrNull { it.type == "USER_SELF" && it.id != exceptId }?.id

    override suspend fun insert(profile: ProfileEntity) {
        rows.removeAll { it.id == profile.id }
        rows += profile
    }

    override suspend fun update(profile: ProfileEntity) {
        rows.replaceAll { if (it.id == profile.id) profile else it }
    }

    override suspend fun deleteById(id: String) {
        rows.removeAll { it.id == id }
    }

    override suspend fun insertLink(link: DocumentProfileLinkEntity) = Unit
    override fun observeAllLinks(): Flow<List<DocumentProfileLinkEntity>> = emptyFlow()
    override suspend fun deleteLink(docId: String, profileId: String) = Unit
    override suspend fun deleteConcernedLink(docId: String, profileId: String) = Unit
    override fun observeProfilesForDocument(documentId: String): Flow<List<ProfileWithRole>> = emptyFlow()
}
