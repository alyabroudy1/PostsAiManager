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

    /**
     * The contacts of [organisationId] that were made from a reading nobody has looked at and that was not sure ([bar]): a letter
     * linked to the contact has a machine "contact" field with the contact's name, unreviewed, below [bar], and no letter has that
     * name confirmed, edited, typed by the user or read with confidence at or above [bar]. Confirming the field clears it.
     */
    @Query(
        """
        SELECT c.id FROM contact_persons c
        WHERE c.organisationId = :organisationId
        AND EXISTS (
            SELECT 1 FROM document_contacts dc INNER JOIN extracted_data e ON e.documentId = dc.documentId
            WHERE dc.contactId = c.id AND e.slotKey = 'contact' AND LOWER(TRIM(e.fieldValue)) = LOWER(TRIM(c.name))
            AND e.source = 'MACHINE' AND e.reviewState = 'UNREVIEWED' AND e.deletedByUser = 0 AND e.confidence < :bar
        )
        AND NOT EXISTS (
            SELECT 1 FROM document_contacts dc INNER JOIN extracted_data e ON e.documentId = dc.documentId
            WHERE dc.contactId = c.id AND e.slotKey = 'contact' AND LOWER(TRIM(e.fieldValue)) = LOWER(TRIM(c.name))
            AND e.deletedByUser = 0
            AND (e.source = 'USER' OR e.reviewState IN ('CONFIRMED', 'EDITED') OR e.confidence >= :bar)
        )
        """,
    )
    fun observeToCheck(organisationId: String, bar: Float): Flow<List<String>>

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

    @Query("SELECT documentId FROM document_contacts WHERE contactId = :contactId")
    suspend fun documentIdsOf(contactId: String): List<String>

    @Query("DELETE FROM document_contacts WHERE contactId = :contactId AND documentId = :documentId")
    suspend fun deleteLink(contactId: String, documentId: String)

    /** Points the documents of [fromId] at [toId]; a document that already has [toId] keeps that one link. */
    @Query("UPDATE OR IGNORE document_contacts SET contactId = :toId WHERE contactId = :fromId")
    suspend fun moveLinks(fromId: String, toId: String)
}
