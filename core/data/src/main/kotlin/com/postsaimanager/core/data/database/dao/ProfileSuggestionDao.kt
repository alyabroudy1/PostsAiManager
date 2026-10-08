package com.postsaimanager.core.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.postsaimanager.core.data.database.entity.ProfileSuggestionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ProfileSuggestionDao {

    @Query("SELECT * FROM profile_suggestions WHERE profileId = :profileId AND status = 'PENDING' ORDER BY createdAt ASC, id ASC")
    fun observePending(profileId: String): Flow<List<ProfileSuggestionEntity>>

    @Query("SELECT * FROM profile_suggestions WHERE profileId = :profileId")
    suspend fun getAll(profileId: String): List<ProfileSuggestionEntity>

    @Query("SELECT * FROM profile_suggestions WHERE id = :id")
    suspend fun getById(id: String): ProfileSuggestionEntity?

    /** A value already offered (pending or dismissed) for the profile's field stays as it is: the first offer wins. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(suggestion: ProfileSuggestionEntity)

    @Query("UPDATE profile_suggestions SET status = :status WHERE id = :id")
    suspend fun setStatus(id: String, status: String)

    @Query("DELETE FROM profile_suggestions WHERE profileId = :profileId AND field = :field AND status = 'PENDING'")
    suspend fun deletePending(profileId: String, field: String)
}
