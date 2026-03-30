package com.postsaimanager.core.model

import kotlinx.serialization.Serializable

/**
 * Data extracted from a document by the OCR + AI pipeline.
 */
@Serializable
data class ExtractedData(
    val id: String,
    val documentId: String,
    val fieldName: String,
    val fieldValue: String,
    val fieldType: ExtractedFieldType,
    val confidence: Float,
    val pageNumber: Int? = null,
    val isConfirmed: Boolean = false,
)

@Serializable
enum class ExtractedFieldType {
    TEXT,
    DATE,
    ADDRESS,
    REFERENCE_NUMBER,
    PERSON_NAME,
    ORGANIZATION,
    PHONE,
    EMAIL,
    IBAN,
    SUBJECT,
    DEADLINE,
    TAG_SUGGESTION,
    OTHER,
}

/**
 * Structured extraction result from the AI-enhanced pipeline.
 */
@Serializable
data class ExtractionResult(
    val documentId: String,
    val language: String?,
    val sender: ExtractedContact? = null,
    val receiver: ExtractedContact? = null,
    val subject: String? = null,
    val date: String? = null,
    val referenceNumbers: List<String> = emptyList(),
    val deadline: String? = null,
    val summary: String? = null,
    val documentType: DocumentType? = null,
    val suggestedTags: List<String> = emptyList(),
    val mentionedPersons: List<MentionedPerson> = emptyList(),
    val fields: List<ExtractedData> = emptyList(),
)

@Serializable
data class ExtractedContact(
    val name: String? = null,
    val organization: String? = null,
    val department: String? = null,
    val street: String? = null,
    val city: String? = null,
    val postalCode: String? = null,
    val country: String? = null,
    val phone: String? = null,
    val email: String? = null,
    val reference: String? = null,
)

@Serializable
data class MentionedPerson(
    val name: String,
    val role: String,
)
