package com.postsaimanager.core.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.postsaimanager.core.data.database.entity.DocumentProfileLinkEntity
import com.postsaimanager.core.data.database.entity.ProfileEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ProfileDao {

    @Query("SELECT * FROM profiles ORDER BY name ASC")
    fun observeAll(): Flow<List<ProfileEntity>>

    @Query("SELECT * FROM profiles WHERE kind = :kind ORDER BY name ASC")
    fun observeByKind(kind: String): Flow<List<ProfileEntity>>

    @Query("SELECT * FROM profiles WHERE householdRole = :role ORDER BY name ASC")
    fun observeByRole(role: String): Flow<List<ProfileEntity>>

    /**
     * A profile matches by its name, its organisation, its own named details (label or value), or, for an organisation, by a contact's
     * name or own named details.
     */
    @Query(
        """
        SELECT * FROM profiles
        WHERE name LIKE '%' || :query || '%' OR organization LIKE '%' || :query || '%' OR customDetails LIKE '%' || :query || '%'
        OR id IN (
            SELECT organisationId FROM contact_persons
            WHERE name LIKE '%' || :query || '%' OR customDetails LIKE '%' || :query || '%'
        )
        """,
    )
    fun search(query: String): Flow<List<ProfileEntity>>

    @Query("SELECT * FROM profiles WHERE id = :id")
    suspend fun getById(id: String): ProfileEntity?

    @Query("""
        SELECT * FROM profiles
        WHERE name LIKE '%' || :name || '%'
        OR (:organization IS NOT NULL AND organization LIKE '%' || :organization || '%')
    """)
    suspend fun findSimilar(name: String, organization: String?): List<ProfileEntity>

    /** The id of another "Me" profile than [exceptId], or null. */
    @Query("SELECT id FROM profiles WHERE householdRole = 'SELF' AND id != :exceptId LIMIT 1")
    suspend fun findOtherSelfId(exceptId: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(profile: ProfileEntity)

    @Update
    suspend fun update(profile: ProfileEntity)

    @Query("DELETE FROM profiles WHERE id = :id")
    suspend fun deleteById(id: String)

    // ── Document-Profile Links ──
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLink(link: DocumentProfileLinkEntity)

    @Query("DELETE FROM document_profile_links WHERE documentId = :docId AND profileId = :profileId")
    suspend fun deleteLink(docId: String, profileId: String)

    @Query("""
        SELECT p.*, dpl.role FROM profiles p
        INNER JOIN document_profile_links dpl ON p.id = dpl.profileId
        WHERE dpl.documentId = :documentId
    """)
    fun observeProfilesForDocument(documentId: String): Flow<List<ProfileWithRole>>

    // ── Merging one organisation into another (see ProfileRepositoryImpl.mergeProfiles) ──

    /** Points the documents of [fromId] at [toId]; a document already linked to [toId] keeps that one link. */
    @Query("UPDATE OR IGNORE document_profile_links SET profileId = :toId WHERE profileId = :fromId")
    suspend fun moveDocumentLinks(fromId: String, toId: String)

    @Query("UPDATE contact_persons SET organisationId = :toId WHERE organisationId = :fromId")
    suspend fun moveContacts(fromId: String, toId: String)

    @Query("UPDATE profile_events SET organisationProfileId = :toId WHERE organisationProfileId = :fromId")
    suspend fun moveEvents(fromId: String, toId: String)

    @Query("UPDATE cases SET organisationProfileId = :toId WHERE organisationProfileId = :fromId")
    suspend fun moveCases(fromId: String, toId: String)

    /** A suggestion the target already has for the same field and value stays as it is (the first offer wins). */
    @Query("UPDATE OR IGNORE profile_suggestions SET profileId = :toId WHERE profileId = :fromId")
    suspend fun moveSuggestions(fromId: String, toId: String)

    @Query("UPDATE document_notes SET profileId = :toId WHERE profileId = :fromId")
    suspend fun moveNotes(fromId: String, toId: String)

    @Query("UPDATE organisation_references SET organisationId = :toId WHERE organisationId = :fromId")
    suspend fun moveReferences(fromId: String, toId: String)
}

data class ProfileWithRole(
    val id: String,
    val kind: String,
    val householdRole: String?,
    val name: String,
    val organization: String?,
    val department: String?,
    val street: String?,
    val city: String?,
    val postalCode: String?,
    val country: String?,
    val phone: String?,
    val email: String?,
    val website: String?,
    val reference: String?,
    val notes: String?,
    val completionScore: Float,
    val missingFields: String?,
    val avatarPath: String?,
    val relationship: String?,
    val birthDate: String?,
    val sensitive: Boolean,
    val customDetails: String?,
    val createdAt: Long,
    val modifiedAt: Long,
    val role: String,
)
