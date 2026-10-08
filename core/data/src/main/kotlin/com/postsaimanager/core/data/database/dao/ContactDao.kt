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

/** Row of [ContactDao.observeToCheck]: a suggested contact and the letter it was found on. */
data class ToCheckRow(val contactId: String, val documentId: String)

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
     * The contacts of [organisationId] that a reading suggested and nobody has answered: a letter linked to the contact has a machine
     * "contact" field with the contact's name, unreviewed, and no letter has that name confirmed, edited or typed by the user. Each row
     * is the contact and the (first) letter it was found on. Confirming or editing the field, here or on the letter, clears it; a
     * contact the user typed has no such field and is never in the list.
     */
    @Query(
        """
        SELECT c.id AS contactId, MIN(dc.documentId) AS documentId FROM contact_persons c
        INNER JOIN document_contacts dc ON dc.contactId = c.id
        INNER JOIN extracted_data e ON e.documentId = dc.documentId
        WHERE c.organisationId = :organisationId
        AND e.slotKey = 'contact' AND LOWER(TRIM(e.fieldValue)) = LOWER(TRIM(c.name))
        AND e.source = 'MACHINE' AND e.reviewState = 'UNREVIEWED' AND e.deletedByUser = 0
        AND NOT EXISTS (
            SELECT 1 FROM document_contacts dc2 INNER JOIN extracted_data e2 ON e2.documentId = dc2.documentId
            WHERE dc2.contactId = c.id AND e2.slotKey = 'contact' AND LOWER(TRIM(e2.fieldValue)) = LOWER(TRIM(c.name))
            AND e2.deletedByUser = 0
            AND (e2.source = 'USER' OR e2.reviewState IN ('CONFIRMED', 'EDITED'))
        )
        GROUP BY c.id
        """,
    )
    fun observeToCheck(organisationId: String): Flow<List<ToCheckRow>>

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
