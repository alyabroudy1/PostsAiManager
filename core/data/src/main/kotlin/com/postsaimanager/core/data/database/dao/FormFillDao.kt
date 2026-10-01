package com.postsaimanager.core.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.postsaimanager.core.data.database.entity.FormFieldEntity
import com.postsaimanager.core.data.database.entity.FormFillEntity
import kotlinx.coroutines.flow.Flow

/** Form fills and their fields: the persisted state of the form-filling conversation. */
@Dao
interface FormFillDao {

    @Query("SELECT * FROM form_fills WHERE id = :id")
    suspend fun getFill(id: String): FormFillEntity?

    @Query("SELECT * FROM form_fills WHERE id = :id")
    fun observeFill(id: String): Flow<FormFillEntity?>

    @Query("SELECT * FROM form_fills WHERE documentId = :documentId ORDER BY createdAt DESC LIMIT 1")
    suspend fun latestForDocument(documentId: String): FormFillEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFill(fill: FormFillEntity)

    @Query("SELECT * FROM form_fields WHERE formFillId = :fillId ORDER BY page ASC, orderIndex ASC")
    suspend fun getFields(fillId: String): List<FormFieldEntity>

    @Query("SELECT * FROM form_fields WHERE formFillId = :fillId ORDER BY page ASC, orderIndex ASC")
    fun observeFields(fillId: String): Flow<List<FormFieldEntity>>

    @Query("SELECT * FROM form_fields WHERE id = :id")
    suspend fun getField(id: String): FormFieldEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFields(fields: List<FormFieldEntity>)

    @Query("DELETE FROM form_fields WHERE formFillId = :fillId")
    suspend fun deleteFields(fillId: String)

    /** Replaces the fields of [fillId] with [fields] in one transaction (a re-run never leaves a half-written form). */
    @Transaction
    suspend fun replaceFields(fillId: String, fields: List<FormFieldEntity>) {
        deleteFields(fillId)
        upsertFields(fields)
    }
}
