package com.postsaimanager.core.model

import kotlinx.serialization.Serializable

/**
 * Relation between two documents.
 */
@Serializable
data class DocumentRelation(
    val id: String,
    val sourceDocId: String,
    val targetDocId: String,
    val relationType: RelationType,
    val createdAt: Long,
)

@Serializable
enum class RelationType {
    REPLY_TO,
    FOLLOW_UP,
    RELATED,
    REFERENCE,
    ATTACHMENT,
    SUPERSEDES,
}

/**
 * A tag that can be applied to documents.
 */
@Serializable
data class Tag(
    val id: String,
    val name: String,
    val color: String? = null,
)

/**
 * User preferences for app configuration.
 */
@Serializable
data class UserPreferences(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val language: AppLanguage = AppLanguage.GERMAN,
    val defaultAiModelId: String? = null,
    val useGpu: Boolean = true,
    val temperature: Float = 0.7f,
    val maxTokens: Int = 2048,
)

@Serializable
enum class ThemeMode { LIGHT, DARK, SYSTEM }

@Serializable
enum class AppLanguage(val code: String, val displayName: String) {
    GERMAN("de", "Deutsch"),
    ARABIC("ar", "العربية"),
    ENGLISH("en", "English"),
}
