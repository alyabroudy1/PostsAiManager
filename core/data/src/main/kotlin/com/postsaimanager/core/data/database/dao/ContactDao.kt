package com.postsaimanager.core.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.postsaimanager.core.data.database.entity.ContactPersonEntity
import com.postsaimanager.core.data.database.entity.DocumentContactEntity
import kotlinx.coroutines.flow.Flow

/** Row of [ContactDao.observeCounts]. */
data class ContactCountRow(val organisationId: String, val count: Int)

@Dao
interface ContactDao {

    @Query("SELECT * FROM contact_persons WHERE organisationId = :organisationId ORDER BY lastSeen DESC, name ASC")
    fun observeForOrganisation(organisationId: String): Flow<List<ContactPersonEntity>>

    @Query(
        """
        SELECT c.* FROM contact_persons c
        INNER JOIN document_contacts dc ON c.id = dc.contactId
        WHERE dc.documentId = :documentId
        ORDER BY c.name ASC
        """,
    )
    fun observeForDocument(documentId: String): Flow<List<ContactPersonEntity>>

    @Query("SELECT organisationId, COUNT(*) AS count FROM contact_persons GROUP BY organisationId")
    fun observeCounts(): Flow<List<ContactCountRow>>

    @Query("SELECT * FROM contact_persons WHERE id = :id")
    suspend fun getById(id: String): ContactPersonEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(contact: ContactPersonEntity)

    @Update
    suspend fun update(contact: ContactPersonEntity)

    @Query("DELETE FROM contact_persons WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE contact_persons SET active = :active WHERE id = :id")
    suspend fun setActive(id: String, active: Boolean)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertLink(link: DocumentContactEntity)

    @Query("DELETE FROM document_contacts WHERE contactId = :contactId AND documentId = :documentId")
    suspend fun deleteLink(contactId: String, documentId: String)

    /** Points the documents of [fromId] at [toId]; a document that already has [toId] keeps that one link. */
    @Query("UPDATE OR IGNORE document_contacts SET contactId = :toId WHERE contactId = :fromId")
    suspend fun moveLinks(fromId: String, toId: String)
}
