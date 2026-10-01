package com.postsaimanager.core.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.postsaimanager.core.data.database.entity.ProfileFactEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ProfileFactDao {

    @Query("SELECT * FROM profile_facts WHERE profileId = :profileId ORDER BY `key` ASC")
    fun observeByProfile(profileId: String): Flow<List<ProfileFactEntity>>

    @Query("SELECT * FROM profile_facts WHERE profileId = :profileId")
    suspend fun getByProfile(profileId: String): List<ProfileFactEntity>

    @Query("SELECT * FROM profile_facts WHERE profileId = :profileId AND `key` = :key")
    suspend fun get(profileId: String, key: String): ProfileFactEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(fact: ProfileFactEntity)

    @Query("DELETE FROM profile_facts WHERE profileId = :profileId AND `key` = :key")
    suspend fun delete(profileId: String, key: String)
}
