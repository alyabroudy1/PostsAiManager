package com.postsaimanager.core.model

import kotlinx.serialization.Serializable

/**
 * Represents a single page within a document.
 */
@Serializable
data class DocumentPage(
    val id: String,
    val documentId: String,
    val pageNumber: Int,
    val imagePath: String,
    val processedPath: String? = null,
    val ocrText: String? = null,
    val ocrConfidence: Float? = null,
    val width: Int = 0,
    val height: Int = 0,
)
