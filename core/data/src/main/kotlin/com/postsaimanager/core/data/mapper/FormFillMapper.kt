package com.postsaimanager.core.data.mapper

import com.postsaimanager.core.data.database.entity.FormFieldEntity
import com.postsaimanager.core.data.database.entity.FormFillEntity
import com.postsaimanager.core.model.FormAwaiting
import com.postsaimanager.core.model.FormField
import com.postsaimanager.core.model.FormFieldKind
import com.postsaimanager.core.model.FormFill
import com.postsaimanager.core.model.FormFillStatus
import com.postsaimanager.core.model.FormRole
import com.postsaimanager.core.model.FormValueSource
import com.postsaimanager.core.model.NormBox
import com.postsaimanager.core.model.ReviewState
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Entity and domain mapping of the form-filling tables. The structured columns (the role map, the confirmed roles, what the
 * conversation waits for, the boxes and the options) are JSON text, read leniently: a value that cannot be parsed reads as
 * absent, never as a failure of the row; an unknown enum name falls back to a safe value.
 */
internal object FormFillMapper {
    private val json = Json { ignoreUnknownKeys = true }
    private val roleMap = MapSerializer(FormRole.serializer(), String.serializer())
    private val roleSet = ListSerializer(FormRole.serializer())
    private val strings = ListSerializer(String.serializer())

    fun toDomain(e: FormFillEntity) = FormFill(
        id = e.id,
        documentId = e.documentId,
        status = enumOr(e.status, FormFillStatus.UNDERSTANDING),
        roleProfiles = decode(roleMap, e.roleProfiles) ?: emptyMap(),
        conversationId = e.conversationId,
        currentFieldId = e.currentFieldId,
        confirmedRoles = decode(roleSet, e.confirmedRoles)?.toSet().orEmpty(),
        localeTag = e.localeTag,
        awaiting = e.awaiting?.let { decode(FormAwaiting.serializer(), it) },
        roundAsked = e.roundAsked,
        readingKey = e.readingKey,
        createdAt = e.createdAt,
        updatedAt = e.updatedAt,
    )

    fun toEntity(f: FormFill) = FormFillEntity(
        id = f.id,
        documentId = f.documentId,
        status = f.status.name,
        roleProfiles = json.encodeToString(roleMap, f.roleProfiles),
        confirmedRoles = json.encodeToString(roleSet, f.confirmedRoles.toList()),
        conversationId = f.conversationId,
        currentFieldId = f.currentFieldId,
        localeTag = f.localeTag,
        awaiting = f.awaiting?.let { json.encodeToString(FormAwaiting.serializer(), it) },
        roundAsked = f.roundAsked,
        readingKey = f.readingKey,
        createdAt = f.createdAt,
        updatedAt = f.updatedAt,
    )

    fun toDomain(e: FormFieldEntity) = FormField(
        id = e.id,
        formFillId = e.formFillId,
        documentId = e.documentId,
        page = e.page,
        labelText = e.labelText,
        labelBox = e.labelBox?.let { decode(NormBox.serializer(), it) },
        fillBox = e.fillBox?.let { decode(NormBox.serializer(), it) },
        kind = enumOr(e.kind, FormFieldKind.TEXT),
        section = e.section,
        options = e.options?.let { decode(strings, it) }.orEmpty(),
        dataKey = e.dataKey,
        role = e.role?.let { name -> FormRole.entries.firstOrNull { it.name == name } },
        confidence = e.confidence,
        value = e.value,
        valueSource = enumOr(e.valueSource, FormValueSource.NONE),
        profileId = e.profileId,
        reviewState = enumOr(e.reviewState, ReviewState.UNREVIEWED),
        required = e.required,
        alreadyFilled = e.alreadyFilled,
        reconfirm = e.reconfirm,
        skipped = e.skipped,
        orderIndex = e.orderIndex,
        updatedAt = e.updatedAt,
    )

    fun toEntity(f: FormField) = FormFieldEntity(
        id = f.id,
        formFillId = f.formFillId,
        documentId = f.documentId,
        page = f.page,
        labelText = f.labelText,
        labelBox = f.labelBox?.let { json.encodeToString(NormBox.serializer(), it) },
        fillBox = f.fillBox?.let { json.encodeToString(NormBox.serializer(), it) },
        kind = f.kind.name,
        section = f.section,
        options = f.options.takeIf { it.isNotEmpty() }?.let { json.encodeToString(strings, it) },
        dataKey = f.dataKey,
        role = f.role?.name,
        confidence = f.confidence,
        value = f.value,
        valueSource = f.valueSource.name,
        profileId = f.profileId,
        reviewState = f.reviewState.name,
        required = f.required,
        alreadyFilled = f.alreadyFilled,
        reconfirm = f.reconfirm,
        skipped = f.skipped,
        orderIndex = f.orderIndex,
        updatedAt = f.updatedAt,
    )

    private fun <T> decode(serializer: kotlinx.serialization.KSerializer<T>, text: String): T? =
        if (text.isBlank()) null else runCatching { json.decodeFromString(serializer, text) }.getOrNull()

    private inline fun <reified E : Enum<E>> enumOr(name: String, fallback: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: fallback
}
