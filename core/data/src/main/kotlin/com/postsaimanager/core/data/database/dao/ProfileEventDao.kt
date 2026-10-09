package com.postsaimanager.core.data.database.dao

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import androidx.room.Upsert
import com.postsaimanager.core.data.database.entity.CaseEntity
import com.postsaimanager.core.data.database.entity.ProfileEventEntity
import com.postsaimanager.core.data.database.entity.ProfileEventPersonEntity
import kotlinx.coroutines.flow.Flow

/** An event with the household persons it concerns (an `@Relation`, so a change of the people re-emits the list). */
data class ProfileEventWithPeople(
    @Embedded val event: ProfileEventEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "eventId",
        entity = ProfileEventPersonEntity::class,
        projection = ["profileId"],
    )
    val personIds: List<String>,
)

/** The timeline's events and matters. Events of a trashed document are never listed. */
@Dao
interface ProfileEventDao {

    // ── events ──

    @Transaction
    @Query(
        """
        SELECT e.* FROM profile_events e
        INNER JOIN documents d ON d.id = e.documentId
        INNER JOIN profile_event_people p ON p.eventId = e.id
        WHERE p.profileId = :profileId AND d.deletedAt IS NULL AND e.userState != 'DELETED'
        ORDER BY e.eventDate DESC, e.recordedAt DESC
        """,
    )
    fun observeForPerson(profileId: String): Flow<List<ProfileEventWithPeople>>

    @Transaction
    @Query(
        """
        SELECT e.* FROM profile_events e
        INNER JOIN documents d ON d.id = e.documentId
        WHERE e.organisationProfileId = :organisationId AND d.deletedAt IS NULL AND e.userState != 'DELETED'
        ORDER BY e.eventDate DESC, e.recordedAt DESC
        """,
    )
    fun observeForOrganisation(organisationId: String): Flow<List<ProfileEventWithPeople>>

    @Transaction
    @Query(
        """
        SELECT e.* FROM profile_events e
        INNER JOIN documents d ON d.id = e.documentId
        WHERE e.caseId = :caseId AND d.deletedAt IS NULL AND e.userState != 'DELETED'
        ORDER BY e.eventDate ASC, e.recordedAt ASC
        """,
    )
    fun observeForCase(caseId: String): Flow<List<ProfileEventWithPeople>>

    @Transaction
    @Query(
        """
        SELECT e.* FROM profile_events e
        INNER JOIN documents d ON d.id = e.documentId
        WHERE e.documentId = :documentId AND d.deletedAt IS NULL AND e.userState != 'DELETED'
        ORDER BY e.eventDate ASC, e.recordedAt ASC
        """,
    )
    fun observeForDocument(documentId: String): Flow<List<ProfileEventWithPeople>>

    /** Every event of the letter, a tombstone ('DELETED') included: the re-read has to know what the user did to the reading's event. */
    @Transaction
    @Query("SELECT * FROM profile_events WHERE documentId = :documentId ORDER BY eventDate ASC, recordedAt ASC")
    suspend fun getForDocument(documentId: String): List<ProfileEventWithPeople>

    @Transaction
    @Query("SELECT * FROM profile_events WHERE caseId = :caseId AND userState != 'DELETED' ORDER BY eventDate ASC, recordedAt ASC")
    suspend fun getForCase(caseId: String): List<ProfileEventWithPeople>

    @Transaction
    @Query("SELECT * FROM profile_events WHERE id = :id")
    suspend fun getById(id: String): ProfileEventWithPeople?

    /** The user's edit: a reading's, an action's or a system event takes the 'EDITED' mark, so a re-read keeps it; the user's own stays as it is. */
    @Query(
        "UPDATE profile_events SET kind = :kind, eventDate = :eventDate, title = :title, " +
            "userState = CASE WHEN source = 'USER' THEN userState ELSE 'EDITED' END WHERE id = :id",
    )
    suspend fun updateByUser(id: String, kind: String, eventDate: Long, title: String)

    /** The user deleted an event they did not write: a tombstone (never listed), so a re-read does not bring it back. */
    @Query("UPDATE profile_events SET userState = 'DELETED' WHERE id = :id")
    suspend fun markDeleted(id: String)

    @Query("DELETE FROM profile_events WHERE id = :id")
    suspend fun deleteById(id: String)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(event: ProfileEventEntity)

    /** Links the event to those of [profileIds] that are still profiles (an id of a deleted profile is dropped, not an error). */
    @Query("INSERT OR IGNORE INTO profile_event_people (eventId, profileId) SELECT :eventId, id FROM profiles WHERE id IN (:profileIds)")
    suspend fun insertPeople(eventId: String, profileIds: List<String>)

    /**
     * Removes what a re-read writes again: the letter's reading event and the system events derived from its old values, unless the user
     * touched them (an edited event or a tombstone, 'EDITED' and 'DELETED', stays).
     */
    @Query("DELETE FROM profile_events WHERE documentId = :documentId AND source IN ('DOCUMENT', 'SYSTEM') AND userState = 'NONE'")
    suspend fun deleteReplaceable(documentId: String)

    @Query("DELETE FROM profile_event_people WHERE eventId IN (SELECT id FROM profile_events WHERE documentId = :documentId)")
    suspend fun deletePeopleOfDocument(documentId: String)

    @Query("UPDATE profile_events SET organisationProfileId = :organisationId, contactId = :contactId WHERE documentId = :documentId")
    suspend fun updateLinks(documentId: String, organisationId: String?, contactId: String?)

    @Query("SELECT id FROM profile_events WHERE documentId = :documentId")
    suspend fun idsOfDocument(documentId: String): List<String>

    // ── cases ──

    @Query("SELECT * FROM cases WHERE id = :caseId")
    fun observeCase(caseId: String): Flow<CaseEntity?>

    @Query(
        """
        SELECT DISTINCT c.* FROM cases c
        INNER JOIN profile_events e ON e.caseId = c.id
        INNER JOIN documents d ON d.id = e.documentId
        INNER JOIN profile_event_people p ON p.eventId = e.id
        WHERE p.profileId = :profileId AND d.deletedAt IS NULL AND e.userState != 'DELETED'
        ORDER BY c.createdAt DESC
        """,
    )
    fun observeCasesForPerson(profileId: String): Flow<List<CaseEntity>>

    @Query("SELECT * FROM cases WHERE organisationProfileId = :organisationId ORDER BY createdAt DESC")
    fun observeCasesForOrganisation(organisationId: String): Flow<List<CaseEntity>>

    @Query("SELECT * FROM cases WHERE id = :caseId")
    suspend fun getCase(caseId: String): CaseEntity?

    @Query("SELECT * FROM cases WHERE organisationProfileId = :organisationId ORDER BY createdAt DESC")
    suspend fun casesOfOrganisation(organisationId: String): List<CaseEntity>

    /** Insert or update in place (a replace would delete the row and null the matter of every event in it). */
    @Upsert
    suspend fun upsertCase(case: CaseEntity)

    @Query("UPDATE cases SET status = :status WHERE id = :caseId")
    suspend fun setCaseStatus(caseId: String, status: String)

    /** A person's status: kept (the events no longer derive it) until they set the matter back to automatic. */
    @Query("UPDATE cases SET status = :status, statusSource = 'USER' WHERE id = :caseId")
    suspend fun setCaseStatusByUser(caseId: String, status: String)

    /** Back to automatic: the status follows the events again, starting from [derived] (what the events say now). */
    @Query("UPDATE cases SET status = :derived, statusSource = 'AUTO' WHERE id = :caseId")
    suspend fun setCaseStatusAutomatic(caseId: String, derived: String)

    /** Every event of the letter belongs to [caseId] (null: to no matter). */
    @Query("UPDATE profile_events SET caseId = :caseId WHERE documentId = :documentId")
    suspend fun updateCaseOfDocument(documentId: String, caseId: String?)

    /** A rename is a person's: the title is then theirs ('USER') and no letter replaces it. */
    @Query("UPDATE cases SET title = :title, titleSource = 'USER' WHERE id = :caseId")
    suspend fun renameCase(caseId: String, title: String)

    @Query("DELETE FROM cases WHERE id = :caseId AND NOT EXISTS (SELECT 1 FROM profile_events WHERE caseId = :caseId AND userState != 'DELETED')")
    suspend fun deleteCaseIfEmpty(caseId: String)
}
