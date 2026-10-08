package com.postsaimanager.core.model

import kotlinx.serialization.Serializable

/**
 * A person inside one organisation profile ("Frau Müller at the Jobcenter"): who answers letters there. Not a [Profile]: the
 * organisation is the party the user deals with, the contact is someone who works there.
 */
@Serializable
data class ContactPerson(
    val id: String,
    /** The organisation [Profile] (kind [ProfileKind.ORGANISATION]) this person works at; the contact goes when it goes. */
    val organisationId: String,
    val name: String,
    /** Job title or form of address as printed ("Sachbearbeiterin"). */
    val title: String? = null,
    /** Team or department. */
    val department: String? = null,
    val phone: String? = null,
    val email: String? = null,
    val room: String? = null,
    /** Epoch millis of the first and the newest letter this person was seen on. */
    val firstSeen: Long,
    val lastSeen: Long,
    /** False once the user marked "no longer responsible"; the contact stays as history. */
    val active: Boolean = true,
    /** Details the user named themselves ("Direct line", "Office hours"), in their order. */
    val customDetails: List<CustomDetail> = emptyList(),
)

/** A number a household person has at one organisation (a customer number, a tax ID); owned by the household person. */
@Serializable
data class OrganisationReference(
    val id: String,
    val organisationId: String,
    /** The household [Profile] the number belongs to. */
    val profileId: String,
    val label: String,
    val value: String,
    val sourceDocumentId: String? = null,
    val createdAt: Long,
)
