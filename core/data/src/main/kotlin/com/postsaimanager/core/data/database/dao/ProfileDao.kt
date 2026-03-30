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

    @Query("SELECT * FROM profiles WHERE type = :type ORDER BY name ASC")
    fun observeByType(type: String): Flow<List<ProfileEntity>>

    @Query("SELECT * FROM profiles WHERE name LIKE '%' || :query || '%' OR organization LIKE '%' || :query || '%'")
    fun search(query: String): Flow<List<ProfileEntity>>

    @Query("SELECT * FROM profiles WHERE id = :id")
    suspend fun getById(id: String): ProfileEntity?

    @Query("""
        SELECT * FROM profiles
        WHERE name LIKE '%' || :name || '%'
        OR (:organization IS NOT NULL AND organization LIKE '%' || :organization || '%')
    """)
    suspend fun findSimilar(name: String, organization: String?): List<ProfileEntity>

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
}

data class ProfileWithRole(
    val id: String,
    val type: String,
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
    val createdAt: Long,
    val modifiedAt: Long,
    val role: String,
)
