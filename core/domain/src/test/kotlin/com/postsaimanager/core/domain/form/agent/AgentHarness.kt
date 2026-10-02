package com.postsaimanager.core.domain.form.agent

import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.agent.AgentContext
import com.postsaimanager.core.domain.agent.AgentEntry
import com.postsaimanager.core.domain.agent.QwenToolCallFormat
import com.postsaimanager.core.domain.agent.ToolResult
import com.postsaimanager.core.domain.agent.ScriptedAgentModel
import com.postsaimanager.core.domain.ai.ActiveModelProvider
import com.postsaimanager.core.domain.ai.EmbeddingService
import com.postsaimanager.core.domain.extraction.v2.ExtractionSchema
import com.postsaimanager.core.domain.form.FakeEmbedder
import com.postsaimanager.core.domain.form.FakePersonDataSource
import com.postsaimanager.core.domain.form.FillValues
import com.postsaimanager.core.domain.form.FormDataKeys
import com.postsaimanager.core.domain.form.FormFixtures
import com.postsaimanager.core.domain.form.FormScript
import com.postsaimanager.core.domain.form.PersonValue
import com.postsaimanager.core.domain.form.RememberDetailUseCase
import com.postsaimanager.core.domain.form.UnderstandFormUseCase
import com.postsaimanager.core.domain.form.fill.FillRequestDetector
import com.postsaimanager.core.domain.form.fill.FormMessageCodec
import com.postsaimanager.core.domain.form.fill.FormModel
import com.postsaimanager.core.model.AiMessage
import com.postsaimanager.core.model.DocumentPage
import com.postsaimanager.core.model.FormField
import com.postsaimanager.core.model.FormFill
import com.postsaimanager.core.model.FormMessage
import com.postsaimanager.core.model.FormRole
import com.postsaimanager.core.model.FormValueSource
import com.postsaimanager.core.model.ProfileType
import com.postsaimanager.core.model.Relationship
import com.postsaimanager.core.testing.FakeConversationRepository
import com.postsaimanager.core.testing.FakeDocumentRepository
import com.postsaimanager.core.testing.FakeFormFillRepository
import com.postsaimanager.core.testing.FakeProfileFactRepository
import com.postsaimanager.core.testing.FakeProfileRepository
import com.postsaimanager.core.testing.FakePromptSession
import com.postsaimanager.core.testing.testDocument
import com.postsaimanager.core.testing.testProfile
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.Locale

/**
 * Everything an agent test needs, over the invented German swim-course form: the fakes, the scripted model, "Me" and the child
 * Ahmad. The form is really read (the understanding pipeline over a scripted scorer); only the chat model is a script of tool calls.
 * [newAgent] builds a second agent over the same stored state, which is how a test restarts the app.
 */
class AgentHarness(
    val withPartner: Boolean = false,
    val documentIsForm: Boolean = true,
    val embedder: EmbeddingService = GermanSwim.embedder,
    me: Map<String, PersonValue> = GermanSwim.me(),
    val wording: FormWording = FormWording.English,
) {
    val nowMs = GermanSwim.NOW
    val today: LocalDate = LocalDate.of(2026, 10, 1)
    val fills = FakeFormFillRepository()
    val conversations = FakeConversationRepository()
    val documents = FakeDocumentRepository()
    val profiles = FakeProfileRepository()
    val facts = FakeProfileFactRepository()
    val session = FakePromptSession().apply { scorer = GermanSwim.script::score }
    val people = FakePersonDataSource(
        mapOf("ahmad" to GermanSwim.ahmad(), "me" to me, "anna" to mapOf("full_name" to FakePersonDataSource.profile("Anna Mustermann", GermanSwim.RECENT))),
    )
    val log = FormChatLog(conversations, documents) { nowMs }
    val model = ScriptedAgentModel()
    val format = QwenToolCallFormat()

    /** Messages that ask for help filling in the form (what the scoring model says Yes to). */
    val fillRequests = mutableSetOf<String>()

    init {
        profiles.seed(
            testProfile(id = "me", name = "Me", type = ProfileType.USER_SELF),
            testProfile(id = "ahmad", name = "Ahmad", type = ProfileType.FAMILY_MEMBER, relationship = Relationship.CHILD, birthDate = "2019-03-12"),
        )
        if (withPartner) profiles.seed(testProfile(id = "anna", name = "Anna", type = ProfileType.FAMILY_MEMBER, relationship = Relationship.PARTNER))
        documents.seed(
            testDocument(
                id = "doc", title = "Anmeldung", language = "de",
                extractionType = if (documentIsForm) ExtractionSchema.FORM_APPLICATION.id else "official_letter",
            ),
        )
        documents.seedPages(
            "doc",
            *FormFixtures.pages(FormFixtures.GERMAN).mapIndexed { i, blocks -> DocumentPage("page-$i", "doc", i + 1, "file:///$i.jpg", ocrBlocks = blocks) }.toTypedArray(),
        )
    }

    private val scoringModel = object : FormModel {
        override suspend fun score(system: String, context: String, statements: List<String>): PamResult<List<Double>> =
            PamResult.Success(statements.map { if (fillRequests.any { r -> context.contains(r) }) 4.0 else -3.0 })
    }

    private val understand = UnderstandFormUseCase(session, { system, user -> "<s>$system|$user<u>" to "<a>" }, embedder)

    val envFor: (String) -> FormToolEnv = { documentId ->
        FormToolEnv(
            documentId = documentId, fills = fills, profiles = profiles, people = people,
            guard = FieldValueGuard(people, today = { today }),
            reader = FormReader(fills, documents, profiles, understand, log, clock = { nowMs }, today = { today }, fallbackLocale = { Locale.GERMANY }),
            remember = RememberDetailUseCase(profiles, facts), fillValues = FillValues(people),
            clock = { nowMs }, today = { today }, fallbackLocale = { Locale.GERMANY }, wording = wording,
        )
    }

    val tools = FormAgentTools(envFor)

    private val activeModels = mockk<ActiveModelProvider> {
        coEvery { activeModelId() } returns null
        coEvery { formModelId() } returns null
    }

    var agent = newAgent()

    fun newAgent(agentTrace: com.postsaimanager.core.domain.agent.AgentTrace = com.postsaimanager.core.domain.agent.AgentTrace.NONE) =
        FormFillAgent(fills, documents, log, tools, model, FillRequestDetector(scoringModel, embedder), activeModels, clock = { nowMs }, agentTrace = agentTrace)

    // ── Scripting the model: a call is written the way the model's own template would ──

    private val specs get() = tools.specFor("doc").tools.specs()

    /** The reply of a model that calls [name] with string arguments. */
    fun call(name: String, vararg args: Pair<String, String>): String = callJson(name, *args.map { it.first to JsonPrimitive(it.second) as JsonElement }.toTypedArray())

    fun callJson(name: String, vararg args: Pair<String, JsonElement>): String = format.renderCall(name, JsonObject(mapOf(*args)), specs)

    fun ask(question: String, vararg chips: String): String = callJson(
        "ask_user",
        "question" to JsonPrimitive(question),
        "chips" to kotlinx.serialization.json.JsonArray(chips.map(::JsonPrimitive)),
    )

    /** Runs one tool directly (as the loop would after validating), with what the user wrote so far in [replies]. */
    suspend fun exec(
        tool: String,
        vararg args: Pair<String, String>,
        replies: List<String> = emptyList(),
        previousEnd: AgentEntry.Call? = null,
        json: Map<String, JsonElement> = emptyMap(),
    ): ToolResult {
        val registry = tools.specFor("doc").tools
        val context = AgentContext(replies, emptyList(), previousEnd, turnStartedByUser = replies.isNotEmpty())
        val arguments = JsonObject(args.associate { it.first to JsonPrimitive(it.second) as JsonElement } + json)
        return registry[tool]!!.execute(arguments, context)
    }

    // ── Reading the stored state ──

    suspend fun messages(): List<AiMessage> = conversations.getMessages("conv-doc").first()

    /** What the chat renders (status lines, questions, cards, page chips), in order. */
    suspend fun shown(): List<Pair<FormMessage, String>> = FormMessageCodec.rendered(messages()).mapNotNull { m -> FormMessageCodec.parse(m)?.let { it to m.content } }

    suspend fun fields(): List<FormField> = fills.fields("fill-doc")

    suspend fun field(label: String): FormField = fields().single { it.labelText == label }

    /** The short id the model uses for the field with [label]. */
    suspend fun alias(label: String): String = FormRefs.fieldAlias(fields(), field(label))

    suspend fun fill(): FormFill = fills.getFill("fill-doc")!!

    fun userTexts(messages: List<AiMessage>): List<String> = messages.filter { it.role == com.postsaimanager.core.model.MessageRole.USER }.map { it.content }

    /** The stored tool results as the model read them. */
    suspend fun results(): List<String> = messages().filter { it.role == com.postsaimanager.core.model.MessageRole.TOOL_RESULT && it.toolCallId != null }.mapNotNull { it.toolResult }
}

/** The scripted knowledge about the German swim-course form (what the model "reads") and the two people it is filled from. */
object GermanSwim {
    val NOW: Long = ZonedDateTime.of(2026, 10, 1, 12, 0, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()
    val RECENT: Long = ZonedDateTime.of(2026, 6, 1, 0, 0, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()
    val OLD: Long = ZonedDateTime.of(2024, 6, 1, 0, 0, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()

    val keys = mapOf(
        "Name des Kindes" to "full_name",
        "Geburtsdatum" to "birth_date",
        "Geburtsort" to "birth_place",
        "Anschrift" to "address",
        "Hat Ihr Kind das Seepferdchen bereits?" to "swim_level",
        "Allergien / Hinweise zur Gesundheit" to "allergies",
        "Name der Erziehungsberechtigten" to "full_name",
        "Telefon (Notfall)" to "phone",
        "E-Mail" to "email",
        "Kontoinhaber" to "account_holder",
        "IBAN" to "iban",
        "Name der Bank" to "bank_name",
        "Ort, Datum" to "today_place",
        "Unterschrift" to "signature",
    )

    val script = FormScript(
        notFields = setOf("Kinder 6–10", "Seite", "Kursgebühr"),
        keyOf = keys,
        sectionRoles = mapOf(
            "Angaben zum Kind" to FormRole.SUBJECT,
            "Erziehungsberechtigte" to FormRole.GUARDIAN,
            "Zahlung per Lastschrift" to FormRole.GUARDIAN,
            "Einverständnis" to FormRole.GUARDIAN,
        ),
        fieldRoles = mapOf("Kontoinhaber" to FormRole.PAYER, "IBAN" to FormRole.PAYER, "Name der Bank" to FormRole.PAYER, "Unterschrift" to FormRole.SIGNER),
        subjects = mapOf("Ahmad" to 3.0, "Me" to -2.0),
        reasonLine = "Kinder 6–10 Jahre · Kursbeginn im Herbst",
    )

    val embedder = FakeEmbedder(intent = { label -> keys[label]?.let(::listOf).orEmpty() })

    private fun p(value: String, at: Long = RECENT, sensitive: Boolean = false, source: FormValueSource = FormValueSource.PROFILE) =
        FakePersonDataSource.profile(value, at, sensitive, source)

    fun ahmad() = mapOf(
        "full_name" to p("Ahmad Mustermann"), "birth_date" to p("2019-03-12"), "birth_place" to p("Beispieldorf"),
        "street" to p("Musterstraße 12"), "postcode" to p("54321"), "city" to p("Beispieldorf"),
    )

    fun me(phoneAt: Long = RECENT) = mapOf(
        "full_name" to p("Mohammad Mustermann"), "phone" to p("0151 2345678", phoneAt), "email" to p("mohammad@example.org"),
        "iban" to p("DE89370400440532013000", sensitive = true, source = FormValueSource.FACT),
        "account_holder" to p("Mohammad Mustermann"), "bank_name" to p("Sparkasse Beispiel"), "city" to p("Beispieldorf"),
    )

    /** What [FormDataKeys] names, so a test can name keys without repeating strings. */
    val SWIM_LEVEL = FormDataKeys.SWIM_LEVEL.id
}
