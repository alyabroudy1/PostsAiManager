package com.postsaimanager.core.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A matter (see `Case`): events of one organisation that belong together. Gone with the organisation. Its reference keys are stored as
 * one text (the normalised keys, each between two `|`) so a key is found by an exact `LIKE`. Added in v23.
 */
@Entity(
    tableName = "cases",
    foreignKeys = [
        ForeignKey(
            entity = ProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["organisationProfileId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("organisationProfileId")],
)
data class CaseEntity(
    @PrimaryKey val id: String,
    val organisationProfileId: String,
    val title: String,
    val referenceKeys: String,
    val status: String,
    val createdAt: Long,
    /** `AUTO` (generated from the letters, follows them) or `USER` (renamed by a person). Added in v28. */
    @ColumnInfo(defaultValue = "'AUTO'") val titleSource: String = "AUTO",
    /** `AUTO` (derived from the events) or `USER` (set by a person). Added in v29. */
    @ColumnInfo(defaultValue = "'AUTO'") val statusSource: String = "AUTO",
)

/**
 * One event of a profile's timeline (see `ProfileEvent`), not to be confused with the document processing log in `timeline_events`.
 * Gone with its document; it only loses its organisation, contact or matter when those go. Added in v23.
 */
@Entity(
    tableName = "profile_events",
    foreignKeys = [
        ForeignKey(
            entity = DocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["documentId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["organisationProfileId"],
            onDelete = ForeignKey.SET_NULL,
        ),
        ForeignKey(
            entity = ContactPersonEntity::class,
            parentColumns = ["id"],
            childColumns = ["contactId"],
            onDelete = ForeignKey.SET_NULL,
        ),
        ForeignKey(
            entity = CaseEntity::class,
            parentColumns = ["id"],
            childColumns = ["caseId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index("documentId"),
        Index("organisationProfileId"),
        Index("contactId"),
        Index("caseId"),
        Index("eventDate"),
    ],
)
data class ProfileEventEntity(
    @PrimaryKey val id: String,
    val documentId: String,
    val kind: String,
    val eventDate: Long,
    val recordedAt: Long,
    val title: String,
    val organisationProfileId: String?,
    val contactId: String?,
    val caseId: String?,
    val source: String,
    /** `NONE`, `EDITED` or `DELETED` (a tombstone); see `EventUserState`. Added in v29. */
    @ColumnInfo(defaultValue = "'NONE'") val userState: String = "NONE",
)

/** The household persons an event concerns; gone with either side. Added in v23. */
@Entity(
    tableName = "profile_event_people",
    primaryKeys = ["eventId", "profileId"],
    foreignKeys = [
        ForeignKey(
            entity = ProfileEventEntity::class,
            parentColumns = ["id"],
            childColumns = ["eventId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["profileId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("eventId"), Index("profileId")],
)
data class ProfileEventPersonEntity(
    val eventId: String,
    val profileId: String,
)
