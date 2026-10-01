package com.postsaimanager.core.domain.form.agent

import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.agent.AgentContext
import com.postsaimanager.core.domain.agent.AgentTool
import com.postsaimanager.core.domain.agent.ToolParams
import com.postsaimanager.core.domain.agent.ToolResult
import com.postsaimanager.core.domain.agent.string
import com.postsaimanager.core.domain.form.AddressComposer
import com.postsaimanager.core.domain.form.FormDataKeys
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** `read_form()`: the fields of the document's form (read on the first call, then stored per document). */
class ReadFormTool(private val env: FormToolEnv) : AgentTool {
    override val name = NAME
    override val description = "Reads the form: one line per field, id|label|page|section|kind|options|key|role|status. " +
        "Key and role are the app's guess of what the field asks for and whose data it needs."
    override val parameters: JsonObject = ToolParams.schema()

    override suspend fun execute(args: JsonObject, context: AgentContext): ToolResult {
        val reading = when (val read = env.reader.read(env.documentId)) {
            is PamResult.Error -> return ToolResult.error("the form could not be read: ${read.error.userMessage}")
            is PamResult.Success -> read.data
        }
        val people = env.managed()
        val fields = FormRefs.ordered(reading.fields)
        return ToolResult.ok(
            buildJsonObject {
                put("pages", fields.maxOfOrNull { it.page } ?: 0)
                put("fields", JsonArray(fields.map { JsonPrimitive(FormRefs.line(fields, it)) }))
                reading.suggestion?.let { s ->
                    people.firstOrNull { it.id == s.profileId }?.let { person ->
                        put(
                            "suggested_person",
                            buildJsonObject {
                                put("person_id", FormRefs.personAlias(people, person))
                                put("name", person.name)
                                s.reasonLine?.let { put("reason_quoted_from_form", it) }
                            },
                        )
                    }
                }
            },
        )
    }

    companion object {
        const val NAME = "read_form"
    }
}

/** `list_people()`: the people forms are filled for. */
class ListPeopleTool(private val env: FormToolEnv) : AgentTool {
    override val name = NAME
    override val description = "Lists the people with stored details: person_id, name, relationship, age, and a child's guardians."
    override val parameters: JsonObject = ToolParams.schema()

    override suspend fun execute(args: JsonObject, context: AgentContext): ToolResult {
        val people = env.managed()
        return ToolResult.ok(
            "people" to JsonArray(
                people.map { person ->
                    buildJsonObject {
                        put("person_id", FormRefs.personAlias(people, person))
                        put("name", person.name)
                        put("relationship", if (person.isSelf) "me (the user)" else person.relationship?.name?.lowercase() ?: "other")
                        FormRefs.ageOf(person, env.today())?.let { put("age", it) }
                        val guardians = env.guardiansOf(person)
                        if (guardians.isNotEmpty()) put("guardians", JsonArray(guardians.map { JsonPrimitive(FormRefs.personAlias(people, it)) }))
                    }
                },
            ),
        )
    }

    companion object {
        const val NAME = "list_people"
    }
}

/** `get_person_details(person_id)`: a person's stored details; a sensitive one is only a `***key` token that `fill_field` resolves. */
class GetPersonDetailsTool(private val env: FormToolEnv, private val addresses: AddressComposer = AddressComposer()) : AgentTool {
    override val name = NAME
    override val description = "A person's stored details by key. A value like ***iban is a secret: pass that token unchanged to fill_field."
    override val parameters: JsonObject = ToolParams.schema(ToolParams.string("person_id", ""))

    override suspend fun execute(args: JsonObject, context: AgentContext): ToolResult {
        val person = env.person(args.string("person_id").orEmpty()) ?: return ToolResult.error("unknown person_id; call list_people")
        val stored = env.people.allOf(person.id)
        val composed = addresses.compose(stored, null)
        return ToolResult.ok(
            buildJsonObject {
                put("name", person.name)
                put(
                    "details",
                    buildJsonObject {
                        stored.forEach { (key, value) ->
                            put(key, if (value.sensitive || FormDataKeys.isSensitive(key)) FormRefs.token(key) else value.value)
                        }
                        if (composed != null && FormDataKeys.ADDRESS.id !in stored) put(FormDataKeys.ADDRESS.id, composed.value)
                    },
                )
            },
        )
    }

    companion object {
        const val NAME = "get_person_details"
    }
}
