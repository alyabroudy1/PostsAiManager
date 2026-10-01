package com.postsaimanager.core.domain.form.fill

import com.postsaimanager.core.domain.ai.EmbeddingService
import com.postsaimanager.core.domain.extraction.v2.ExtractionSchema
import com.postsaimanager.core.domain.form.FakeEmbedder
import com.postsaimanager.core.domain.form.FakePersonDataSource
import com.postsaimanager.core.domain.form.FillValues
import com.postsaimanager.core.domain.form.FormFixtures
import com.postsaimanager.core.domain.form.FormDataKeys
import com.postsaimanager.core.domain.form.FormScript
import com.postsaimanager.core.domain.form.GuardiansOfUseCase
import com.postsaimanager.core.domain.form.RememberDetailUseCase
import com.postsaimanager.core.domain.form.UnderstandFormUseCase
import com.postsaimanager.core.model.AiMessage
import com.postsaimanager.core.model.DocumentPage
import com.postsaimanager.core.model.FormChip
import com.postsaimanager.core.model.FormField
import com.postsaimanager.core.model.FormFill
import com.postsaimanager.core.model.FormMessage
import com.postsaimanager.core.model.FormMessageKind
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
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.Locale

/**
 * Everything a conversation test needs, over the invented German swim-course form: the fakes, the scripted models, "Me" and the
 * child Ahmad. [newConversation] builds a second conversation over the same stored state, which is how a test restarts the app.
 */
class FillHarness(
    val model: FakeFormModel = FakeFormModel(),
    val profile: FormFillProfile = FormFillProfile(),
    val withPartner: Boolean = false,
    val documentIsForm: Boolean = true,
    val embedder: EmbeddingService = GermanSwim.embedder,
    me: Map<String, com.postsaimanager.core.domain.form.PersonValue> = GermanSwim.me(),
) {
    val nowMs = GermanSwim.NOW
    val fills = FakeFormFillRepository()
    val conversations = FakeConversationRepository()
    val documents = FakeDocumentRepository()
    val profiles = FakeProfileRepository()
    val facts = FakeProfileFactRepository()
    val session = FakePromptSession().apply { scorer = GermanSwim.script::score }
    val people = FakePersonDataSource(
        mapOf("ahmad" to GermanSwim.ahmad(), "me" to me, "anna" to mapOf("full_name" to FakePersonDataSource.profile("Anna Mustermann", GermanSwim.RECENT))),
    )

    init {
        profiles.seed(
            testProfile(id = "me", name = "Me", type = ProfileType.USER_SELF),
            testProfile(id = "ahmad", name = "Ahmad", type = ProfileType.FAMILY_MEMBER, relationship = Relationship.CHILD, birthDate = "2019-03-12"),
        )
        if (withPartner) profiles.seed(testProfile(id = "anna", name = "Anna", type = ProfileType.FAMILY_MEMBER, relationship = Relationship.PARTNER))
        documents.seed(testDocument(id = "doc", title = "Anmeldung", extractionType = if (documentIsForm) ExtractionSchema.FORM_APPLICATION.id else "official_letter"))
        documents.seedPages(
            "doc",
            *FormFixtures.pages(FormFixtures.GERMAN).mapIndexed { i, blocks ->
                DocumentPage("page-$i", "doc", i + 1, "file:///$i.jpg", ocrBlocks = blocks)
            }.toTypedArray(),
        )
    }

    var conversation = newConversation()

    fun newConversation(): FormFillConversation {
        val interpreter = AnswerInterpreter(model, profile)
        return FormFillConversation(
            fills = fills, conversations = conversations, documents = documents, profiles = profiles, people = people,
            guardiansOf = GuardiansOfUseCase(profiles), remember = RememberDetailUseCase(profiles, facts),
            understand = UnderstandFormUseCase(session, { system, user -> "<s>$system|$user<u>" to "<a>" }, GermanSwim.embedder),
            model = model, classifier = FormIntentClassifier(model, profile), detector = FillRequestDetector(model, embedder, profile),
            interpreter = interpreter, writer = FormQuestionWriter(model, { Locale.GERMAN }, profile),
            answerChips = AnswerChips(people, profile), fillValues = FillValues(people), profile = profile,
            clock = { nowMs }, today = { LocalDate.of(2026, 10, 1) }, fallbackLocale = { Locale.GERMANY },
        )
    }

    suspend fun messages(): List<AiMessage> = conversations.getMessages("conv-doc").first()

    suspend fun forms(): List<FormMessage> = messages().mapNotNull(FormMessageCodec::parse)

    /** The most recent question, with the text the model wrote for it (blank for a template). */
    suspend fun lastQuestion(): Pair<FormMessage, String> {
        val message = messages().last { FormMessageCodec.parse(it)?.kind == FormMessageKind.QUESTION }
        return FormMessageCodec.parse(message)!! to message.content
    }

    suspend fun fields(): List<FormField> = fills.fields("fill-doc")

    suspend fun field(label: String): FormField = fields().single { it.labelText == label }

    suspend fun fill(): FormFill = fills.getFill("fill-doc")!!

    fun shown(chip: FormChip): String = chip.label ?: chip.labelCode!!.name

    suspend fun tap(chip: FormChip) = conversation.chip("doc", chip, shown(chip))

    /** Taps the chip of the last question whose label is [label]. */
    suspend fun tap(label: String) {
        val chip = lastQuestion().first.chips.first { shown(it) == label }
        tap(chip)
    }

    suspend fun say(text: String): FormRoute = conversation.route("doc", text)

    /** Starts the fill and answers "Who is this form for?" with Ahmad. */
    suspend fun startForAhmad() {
        conversation.start("doc")
        tap("Ahmad")
    }

    fun userTexts(messages: List<AiMessage>): List<String> = messages.filter { it.role == com.postsaimanager.core.model.MessageRole.USER }.map { it.content }
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
